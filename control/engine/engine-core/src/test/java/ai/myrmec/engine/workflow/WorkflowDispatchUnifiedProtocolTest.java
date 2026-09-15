// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Discriminating cutover test: a workflow task dispatches through the unified
 * host-control protocol and NEVER through the legacy task-delivery wire.
 *
 * <p>The discriminator is the frame sequence on the host socket plus the
 * durable session/execution state (P6-T6: the legacy task-delivery wire is
 * deleted, so "never through the legacy path" is enforced by the codebase,
 * not by a spy). The unified path offers a session ({@code session.offer}),
 * waits for the host's accept/opened answers, and ships {@code session.open}
 * then {@code execution.start}.</p>
 */
class WorkflowDispatchUnifiedProtocolTest extends WorkflowDispatchSupport {

    @Autowired private TaskDispatcherService dispatcher;
    @Autowired private SessionAllocator sessionAllocator;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationDispatchRepository dispatchRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private TaskAttemptRepository attemptRepository;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() throws Exception {
        java.nio.file.Path origin = java.nio.file.Files.createTempDirectory("unified-origin-");
        origin = origin.resolve("origin.git");
        git(origin.getParent(), "init", "--bare", "-b", "main", origin.toString());
        java.nio.file.Path seed = java.nio.file.Files.createTempDirectory("unified-seed-");
        git(seed, "init", "-b", "main");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "seed");
        String originUrl = origin.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "push", originUrl, "main");

        project = data.project().named("unified").withRepo(originUrl, "main").create();
        profile = data.agentProfile().named("unified-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("an ORCHESTRATOR task dispatches via session.offer → session.open(assignment) → execution.start; the legacy relay send never runs")
    void orchestrationTaskRidesTheUnifiedWire() throws Exception {
        SocketHost host = openHost("unified-host", profile, project, 4);
        Workflow wf = orchestratorWorkflow("unified");
        WorkflowRequest request = runningRequest(wf, "unified");
        runService.pinRun(request.getId(), wf.getId(), project.getId(), profile.getId());
        WorkflowTask task = pendingTask(request, "build");

        dispatcher.dispatchPendingTasks();

        // ---- §7.1: the engine OFFERED a session; nothing started yet ----
        UUID sessionId = offeredSessionId(host);
        JsonNode offer = framesOf(host).stream()
                .filter(f -> "session.offer".equals(f.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(offer.path("payload").path("kind").asText()).isEqualTo("ORCHESTRATION_TASK");
        assertThat(offer.path("payload").path("ref").path("id").asText())
                .isEqualTo(request.getId().toString());

        Session offered = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(offered.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_OFFERED);
        assertThat(offered.getHostInstanceId()).isEqualTo(host.instanceId());
        // §7.4: no execution may exist before session.opened.
        assertThat(sessionExecutionRepository.findBySessionId(sessionId)).isEmpty();
        // The attempt is engine-authored and exists already (dispatchId).
        TaskAttempt attempt = attemptRepository
                .findFirstByTaskIdOrderByAttemptNumberDesc(task.getId()).orElseThrow();
        // §16.3: the assignment was recorded durably BEFORE any send.
        OrchestrationDispatch dispatch = dispatchRepository
                .findById(attempt.getId()).orElseThrow();
        assertThat(dispatch.getAssignmentDigest()).hasSize(64);
        assertThat(dispatch.getDeliveryState()).isEqualTo("PENDING");

        // ---- §7.2/§7.3: accept → session.open carries the §16.2 assignment ----
        acceptSession(host, sessionId);
        JsonNode sessionOpen = awaitFrame(host.outbound(), "session.open");
        assertThat(sessionOpen.path("payload").path("serviceType").asText()).isEqualTo("WORKFLOW");
        JsonNode assignment = sessionOpen.path("payload").path("orchestration");
        assertThat(assignment.isObject())
                .as("the §16.2 assignment rides session.open for an orchestration session")
                .isTrue();
        assertThat(assignment.path("dispatch").path("dispatchId").asText())
                .isEqualTo(attempt.getId().toString());
        assertThat(sessionOpen.path("payload").path("assignmentDigest").asText())
                .as("session.open carries the digest of the stored canonical bytes")
                .isEqualTo(dispatch.getAssignmentDigest());
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_INITIALIZING);

        // ---- §7.4/§8.1: opened → execution.start references the stored bytes ----
        openSession(host, sessionId);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);

        JsonNode start = awaitFrame(host.outbound(), "execution.start");
        UUID executionId = UUID.fromString(start.path("executionId").asText());
        JsonNode startPayload = start.path("payload");
        assertThat(startPayload.path("dispatchId").asText())
                .as("§8.1's orchestration shape carries the dispatch identity")
                .isEqualTo(attempt.getId().toString());
        assertThat(startPayload.path("attemptId").asText()).isEqualTo(attempt.getId().toString());
        assertThat(startPayload.path("assignmentDigest").asText())
                .isEqualTo(dispatch.getAssignmentDigest());
        assertThat(startPayload.path("input").isMissingNode()
                        || startPayload.path("input").isNull())
                .as("the assignment is installed at session.open, not re-sent on the execution")
                .isTrue();

        // The execution row is correlated with the durable dispatch already.
        assertThat(sessionExecutionRepository.findById(executionId).orElseThrow()
                .getDispatchId()).isEqualTo(attempt.getId());

        // The task + attempt are bound to the serving worker the allocator minted.
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(stored.getAgentInstance()).isNotNull();
        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getAgentInstance())
                .isNotNull();
        assertThat(runRepository.findById(request.getId()).orElseThrow().getCoordinatorHostId())
                .as("the §16.4 coordinator binding is the selected host")
                .isEqualTo(host.host().getId());

        // ---- The discriminator: NO legacy delivery happened ----
        // (P6-T6: the legacy wire is deleted from the engine; the unified
        // frame sequence below is the only delivery path that exists.)
        for (JsonNode frame : framesOf(host)) {
            assertThat(frame.path("type").asText())
                    .as("no legacy task-delivery frame on the unified wire")
                    .isNotEqualTo("inference.assign");
        }
        assertThat(offeredSessionId(host)).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("an ordinary INFERENCE task dispatches via session.offer → session.open → execution.start with the §8.1 input block; no assignment, no relay")
    void ordinaryTaskRidesTheUnifiedWire() throws Exception {
        SocketHost host = openHost("unified-plain-host", profile, project, 4);
        Workflow wf = plainInferenceWorkflow("unified-plain");
        WorkflowRequest request = runningRequest(wf, "unified-plain");
        WorkflowTask task = pendingTask(request, "analyze");

        dispatcher.dispatchPendingTasks();

        UUID sessionId = offeredSessionId(host);
        acceptSession(host, sessionId);
        JsonNode sessionOpen = awaitFrame(host.outbound(), "session.open");
        assertThat(sessionOpen.path("payload").path("orchestration").isMissingNode()
                        || sessionOpen.path("payload").path("orchestration").isNull())
                .as("an ordinary step installs no assignment at session.open")
                .isTrue();

        openSession(host, sessionId);
        JsonNode start = awaitFrame(host.outbound(), "execution.start");
        JsonNode startPayload = start.path("payload");
        assertThat(startPayload.path("input").path("messages").isArray())
                .as("the §8.1 input block rides execution.start for an ordinary step")
                .isTrue();
        assertThat(startPayload.path("toolPolicy").path("approvalMode").asText())
                .isEqualTo("ENGINE");
        assertThat(startPayload.path("dispatchId").isMissingNode()
                        || startPayload.path("dispatchId").isNull())
                .as("an ordinary step carries no orchestration dispatch identity")
                .isTrue();

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(dispatchRepository.findAll()).isEmpty();
        assertThat(runRepository.findById(request.getId()))
                .as("an ordinary inference request never pins a run row")
                .isEmpty();
    }

    @Test
    @DisplayName("a workflow terminal returns the slot: the WORKFLOW session closes and capacity is available again")
    void workflowTerminalClosesTheSession() throws Exception {
        SocketHost host = openHost("unified-term-host", profile, project, 1);
        Workflow wf = plainInferenceWorkflow("unified-term");
        WorkflowRequest request = runningRequest(wf, "unified-term");
        WorkflowTask task = pendingTask(request, "analyze");

        dispatcher.dispatchPendingTasks();
        UUID sessionId = completeHandshake(host);
        JsonNode start = awaitFrame(host.outbound(), "execution.start");
        UUID executionId = UUID.fromString(start.path("executionId").asText());

        sendHost(host, """
                { "protocolVersion": 1, "messageId": "m-accept-exec", "type": "execution.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "startedAt": "%s", "resolvedModelId": "m1",
                  "dispatchId": "%s", "assignmentDigest": "abc" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, executionId,
                executionId, Instant.now(),
                attemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(task.getId())
                        .orElseThrow().getId()));

        sendHost(host, """
                { "protocolVersion": 1, "messageId": "m-term", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "step done", "structured": null, "artifacts": [] },
                  "usage": { "modelId": "m1", "inputTokens": 3, "outputTokens": 5, "durationMs": 10 } } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, executionId,
                executionId, Instant.now()));

        // The attempt/task completed through the workflow sink.
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.SUCCESS);
        assertThat(stored.getOutput()).containsEntry("content", "step done");

        // §9: a workflow session is one-shot — the slot returns to the pool.
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(sessionAllocator.offer(TaskDispatcherService.SESSION_KIND_TASK,
                UUID.randomUUID(), TaskDispatcherService.SERVICE_TYPE_WORKFLOW,
                project.getId(), host.host().getId()))
                .as("the released slot accepts a new reservation")
                .isPresent();
    }

    // ── fixtures ───────────────────────────────────────────────

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

    private WorkflowTask pendingTask(WorkflowRequest request, String stepId) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId(stepId);
        task.setAgentProfile(profile);
        task.setInput(Map.of("goal", "do the thing"));
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        task.setMaxRetries(0);
        return taskRepository.save(task);
    }

    private Workflow plainInferenceWorkflow(String tag) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "analyze");
        step.put("name", "Analyze");
        step.put("agentProfileId", profile.getId().toString());
        step.put("prompt", "Summarize the repo");
        step.put("dependsOn", List.of());
        step.put("transitions", Map.of());
        step.put("timeoutSeconds", 300);
        step.put("maxRetries", 0);
        step.put("pauseMode", "NONE");

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of(step));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private Workflow orchestratorWorkflow(String tag) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "build");
        step.put("name", "Build");
        step.put("agentProfileId", profile.getId().toString());
        step.put("prompt", "Orchestrate");
        step.put("dependsOn", List.of());
        step.put("transitions", Map.of());
        step.put("timeoutSeconds", 600);
        step.put("maxRetries", 1);
        step.put("pauseMode", "NONE");
        step.put("taskType", "ORCHESTRATOR");
        Map<String, Object> orch = new LinkedHashMap<>();
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
        step.put("retryPolicy", Map.of(
                "maxRetries", 1, "initialBackoffSeconds", 2, "maxBackoffSeconds", 30));

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of(step));
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    private static void git(java.nio.file.Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        Process p = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: "
                    + new String(p.getInputStream().readAllBytes()));
        }
    }

}
