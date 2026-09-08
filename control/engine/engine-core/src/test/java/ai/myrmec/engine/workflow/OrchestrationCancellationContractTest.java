// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.websocket.InboundOrchestrationHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §16.6 cancellation contract + §19.3 "cancellation
 * acknowledgement/timeout": a user cancel marks the request CANCELLED,
 * running tasks CANCELLED, the active attempt ABANDONED, and sends
 * inference.cancel best-effort; a later Agent cancellation result is a
 * cleanup acknowledgement that can never resurrect the request; a
 * terminal run whose release acknowledgement never arrives is
 * force-expired after the ack timeout.
 */
class OrchestrationCancellationContractTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowRequestService requestService;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;
    @Autowired private InboundOrchestrationHandler inboundHandler;
    @Autowired private WorkspaceReleaseReconciliationSweeper releaseSweeper;
    @Autowired private ObjectMapper objectMapper;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() {
        project = data.project().named("cancel").withRepo("https://x.git", "main").create();
        profile = data.agentProfile().named("cancel-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("cancel: request CANCELLED, running task CANCELLED, attempt ABANDONED; a late CANCELLED result is a cleanup acknowledgement")
    void cancelCascadeAndLateResultIsCleanupAck() throws Exception {
        Workflow wf = orchestratorWorkflow("cancel-cascade");
        WorkflowRequest request = runningRequest(wf, "cancel-cascade");
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());
        WorkflowTask task = runningOrchestrationTask(request);
        TaskAttempt attempt = task.createAttempt(null);
        attemptRepository.save(attempt);

        // User cancel.
        requestService.cancel(request.getId());

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.CANCELLED);
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        TaskAttempt storedAttempt = attemptRepository
                .findFirstByTaskIdOrderByAttemptNumberDesc(task.getId()).orElseThrow();
        assertThat(storedAttempt.getStatus()).isEqualTo(AttemptStatus.ABANDONED);

        // The agent's late CANCELLED result arrives — a cleanup
        // acknowledgement. It must NOT resurrect or overwrite anything.
        UUID resultId = UUID.randomUUID();
        inboundHandler.onOrchestrationResult(attempt.getAgentInstance() == null
                        ? UUID.randomUUID() : attempt.getAgentInstance().getId(),
                objectMapper.readTree(resultFrame(request.getId(), task.getId(),
                        attempt.getId(), resultId, "CANCELLED", "cleanup ack")));

        // Same terminal state everywhere — the frame was a no-op beyond
        // its own attempt row (the attempt is already ABANDONED; a
        // CANCELLED replay cannot flip it back).
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .as("a late cancellation result never resurrects the request")
                .isEqualTo(RequestStatus.CANCELLED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("release ack timeout: a terminal run with a live lease is force-expired past the window")
    void releaseAcknowledgementTimeoutForceExpires() {
        Workflow wf = orchestratorWorkflow("cancel-timeout");
        WorkflowRequest request = runningRequest(wf, "cancel-timeout");
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());

        // Terminal request + still-live lease (release was best-effort and
        // the agent never acknowledged).
        OrchestrationRun run = runRepository.findById(request.getId()).orElseThrow();
        run.setLeaseState("RELEASING");
        run.setLeaseDeadline(Instant.now().plus(Duration.ofSeconds(60)));
        runRepository.save(run);
        request.setStatus(RequestStatus.CANCELLED);
        // completedAt far in the past — outside the ack window.
        request.setCompletedAt(Instant.now().minus(Duration.ofSeconds(10_000)));
        requestRepository.save(request);

        releaseSweeper.reconcileUnacknowledgedReleases();

        run = runRepository.findById(request.getId()).orElseThrow();
        assertThat(run.getLeaseState())
                .as("an unacknowledged release past the window force-expires the lease")
                .isEqualTo("LOST");
        assertThat(run.getLeaseDeadline()).isNull();

        // Inside the window: untouched.
        WorkflowRequest fresh = runningRequest(wf, "cancel-window");
        runService.pinRun(fresh.getId(), wf.getId(), project.getId(), profile.getId());
        OrchestrationRun freshRun = runRepository.findById(fresh.getId()).orElseThrow();
        freshRun.setLeaseState("RELEASING");
        freshRun.setLeaseDeadline(Instant.now().plus(Duration.ofSeconds(60)));
        runRepository.save(freshRun);
        fresh.setStatus(RequestStatus.CANCELLED);
        fresh.setCompletedAt(Instant.now()); // just now — inside the window
        requestRepository.save(fresh);

        releaseSweeper.reconcileUnacknowledgedReleases();
        assertThat(runRepository.findById(fresh.getId()).orElseThrow().getLeaseState())
                .as("a terminal run inside the ack window keeps its lease")
                .isEqualTo("RELEASING");

        // A non-terminal run is never touched (renewal owns it).
        WorkflowRequest live = runningRequest(wf, "cancel-live");
        runService.pinRun(live.getId(), wf.getId(), project.getId(), profile.getId());
        OrchestrationRun liveRun = runRepository.findById(live.getId()).orElseThrow();
        liveRun.setLeaseState("ACTIVE");
        liveRun.setLeaseDeadline(Instant.now().plus(Duration.ofSeconds(60)));
        runRepository.save(liveRun);

        releaseSweeper.reconcileUnacknowledgedReleases();
        assertThat(runRepository.findById(live.getId()).orElseThrow().getLeaseState())
                .as("a live run's lease is the renewal service's domain")
                .isEqualTo("ACTIVE");
    }

    // ── fixtures ───────────────────────────────────────────────

    private String resultFrame(UUID runId, UUID taskId, UUID dispatchId,
                               UUID resultId, String status, String summary) {
        return """
                {"resultId":"%s","resultDigest":"%s","runId":"%s",\
                "dispatch":{"workflowId":"wf-1","runId":"%s","stepId":"build",\
                "taskId":"%s","attemptId":"%s","attemptOrdinal":1,"dispatchId":"%s"},\
                "status":"%s","retryDisposition":"NONE","summary":"%s",\
                "usage":{"workerCalls":0,"rejectionCount":0,"totalTokens":0}}
                """.formatted(resultId, "digest-" + resultId, runId, runId, taskId,
                dispatchId, dispatchId, status, summary);
    }

    private WorkflowRequest runningRequest(Workflow wf, String tag) {
        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/" + tag);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        return requestRepository.save(req);
    }

    private WorkflowTask runningOrchestrationTask(WorkflowRequest request) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(0);
        return taskRepository.save(task);
    }

    private Workflow orchestratorWorkflow(String tag) {
        Map<String, Object> step = new java.util.LinkedHashMap<>();
        step.put("id", "build");
        step.put("name", "Build");
        step.put("agentProfileId", profile.getId().toString());
        step.put("prompt", "Orchestrate");
        step.put("dependsOn", List.of());
        step.put("transitions", Map.of());
        step.put("timeoutSeconds", 600);
        step.put("maxRetries", 0);
        step.put("pauseMode", "NONE");
        step.put("taskType", "ORCHESTRATOR");
        Map<String, Object> orch = new java.util.LinkedHashMap<>();
        orch.put("modelCode", TEST_MODEL_CODE);
        orch.put("goal", "write a module");
        orch.put("sourceSubPath", ".");
        orch.put("workers", List.of());
        orch.put("checkpointStrategy", Map.of(
                "mode", "ON_VERIFICATION_PASS", "commitMessage", "checkpoint",
                "pushToRemote", false, "allowNoChanges", true));
        orch.put("completionCriteria", Map.of(
                "definitionOfDone", "compiles", "requireVerificationBy", List.of()));
        orch.put("budget", Map.of(
                "maxTokens", 50000, "maxWorkerCalls", 10,
                "maxVerifierRejectionsPerAttempt", 3,
                "maxOrchestratorIterations", 5, "maxWorkerIterations", 5,
                "onBudgetExceeded", "FAIL"));
        step.put("orchestration", orch);
        step.put("retryPolicy", Map.of("maxRetries", 0));

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of(step));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }
}