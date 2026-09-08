// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §16.5 engine-owned lease renewal + the §19.3 "pause/continue,
 * pause/stop" contract for orchestrated tasks (J3 via the shared
 * dispatch path): live leases renew; a paused request's lease extends to
 * approval deadline + recovery grace; terminal requests never renew;
 * PAUSED_BEFORE/PAUSED_AFTER orchestration tasks continue and stop
 * through the operator endpoints.
 */
class OrchestrationLeaseRenewalTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private OrchestrationRunService runService;
    @Autowired private OrchestrationRunRepository runRepository;
    @Autowired private OrchestrationLeaseRenewalService leaseRenewal;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRequestRepository requestRepository;
    @Autowired private WorkflowTaskRepository taskRepository;
    @Autowired private WorkflowTaskPauseService pauseService;

    private static final long RENEWAL_SECONDS = 600L;
    private static final long GRACE_SECONDS = 3600L;

    private Project project;
    private AgentProfile profile;
    private User admin;

    @BeforeEach
    void setUp() {
        project = data.project().named("lease").withRepo("https://x.git", "main").create();
        profile = data.agentProfile().named("lease-profile").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("live leases renew; paused extends to approval deadline + grace; terminal never renews")
    void leaseRenewalSemantics() {
        // A RUNNING request with an ACTIVE lease: renewed forward.
        WorkflowRequest running = runningRequest("lease-live");
        pinActiveLease(running.getId());
        leaseRenewal.renewLeases();
        OrchestrationRun run = runRepository.findById(running.getId()).orElseThrow();
        assertThat(run.getLeaseState()).isEqualTo("ACTIVE");
        assertThat(run.getLeaseDeadline())
                .as("a live lease renews to now + renewal window")
                .isAfter(Instant.now().plus(Duration.ofSeconds(RENEWAL_SECONDS - 60)));

        // A PAUSED request with a pending approval: the lease extends to
        // approval deadline + recovery grace (§16.5).
        WorkflowRequest paused = runningRequest("lease-paused");
        pinActiveLease(paused.getId());
        paused.setStatus(RequestStatus.PAUSED);
        requestRepository.save(paused);
        WorkflowTask approvalTask = pausedTask(paused.getId());
        Instant approvalDeadline = Instant.now().plus(Duration.ofSeconds(120));
        approvalTask.setApprovalExpiresAt(approvalDeadline);
        approvalTask.setApprovalStatus("PENDING");
        taskRepository.save(approvalTask);

        leaseRenewal.renewLeases();
        run = runRepository.findById(paused.getId()).orElseThrow();
        assertThat(run.getLeaseState()).isEqualTo("SUSPENDED");
        // H2 truncates timestamps to micro precision — 1s tolerance.
        assertThat(Duration.between(
                        approvalDeadline.plus(Duration.ofSeconds(GRACE_SECONDS)),
                        run.getLeaseDeadline()).abs().toSeconds())
                .as("a paused lease extends to approval deadline + recovery grace")
                .isZero();

        // A terminal request: never renewed (release owns it).
        WorkflowRequest terminal = runningRequest("lease-terminal");
        pinActiveLease(terminal.getId());
        terminal.setStatus(RequestStatus.COMPLETED);
        terminal.setCompletedAt(Instant.now());
        requestRepository.save(terminal);
        Instant deadlineBefore = runRepository.findById(terminal.getId()).orElseThrow()
                .getLeaseDeadline();
        leaseRenewal.renewLeases();
        assertThat(runRepository.findById(terminal.getId()).orElseThrow().getLeaseDeadline())
                .as("a terminal request's lease is never renewed")
                .isEqualTo(deadlineBefore);
    }

    @Test
    @DisplayName("an orchestrated task pauses BEFORE dispatch and continues via the operator endpoint")
    void orchestratorTaskPauseBeforeAndContinue() {
        WorkflowRequest request = runningRequest("lease-pb");
        // J3 BEFORE gate on the orchestrator step.
        WorkflowTask task = pendingTask(request.getId(), "PAUSED_BEFORE-gate");
        task.setPauseMode(PauseMode.BEFORE);
        task.setPauseState("NONE");
        task = taskRepository.save(task);

        // The dispatcher's pass applies PAUSED_BEFORE (shared path — no
        // orchestrator/inference distinction).
        dispatcher.dispatchPendingTasks();
        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(stored.getPauseState()).isEqualTo("PAUSED_BEFORE");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.PAUSED);

        // Continue: back to PENDING for dispatch; request RUNNING.
        pauseService.continueTask(task.getId());
        stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(stored.getPauseState()).isEqualTo("RESUMED_BEFORE");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.RUNNING);
    }

    @Test
    @DisplayName("a stopped paused orchestration task fails the request (pause/stop)")
    void orchestratorTaskPauseStop() {
        WorkflowRequest request = runningRequest("lease-ps");
        WorkflowTask task = pendingTask(request.getId(), "PAUSED-before-gate");
        task.setStatus(TaskStatus.PAUSED);
        task.setPauseState("PAUSED_BEFORE");
        task.setPauseMode(PauseMode.BEFORE);
        task.setPausedAt(Instant.now());
        taskRepository.save(task);
        request.setStatus(RequestStatus.PAUSED);
        requestRepository.save(request);

        pauseService.stopTask(task.getId(), "operator decided");

        WorkflowTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(stored.getPauseState()).isEqualTo("STOPPED");
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.FAILED);
    }

    // ── fixtures ───────────────────────────────────────────────

    private WorkflowRequest runningRequest(String tag) {
        Workflow wf = plainWorkflow(tag);
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

    private void pinActiveLease(UUID requestId) {
        runService.pinRun(requestId, workflowRepository.findAll().get(0).getId(),
                project.getId(), profile.getId());
        OrchestrationRun run = runRepository.findById(requestId).orElseThrow();
        run.setLeaseState("ACTIVE");
        run.setLeaseDeadline(Instant.now().plus(Duration.ofSeconds(60)));
        runRepository.save(run);
    }

    private WorkflowTask pendingTask(UUID requestId, String stepId) {
        WorkflowTask task = new WorkflowTask();
        task.setRequest(requestRepository.findById(requestId).orElseThrow());
        task.setStepId(stepId);
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        task.setMaxRetries(0);
        return taskRepository.save(task);
    }

    private WorkflowTask pausedTask(UUID requestId) {
        return pendingTask(requestId, "build");
    }

    private Workflow plainWorkflow(String tag) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        return workflowRepository.save(wf);
    }

    @Autowired private TaskDispatcherService dispatcher;
}