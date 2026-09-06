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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature 10 (design §16.6): the exact outcome tuples. Every runner status
 * maps to one precise (attempt, task, request) state transition under the
 * attempt row lock; duplicate resultIds replay the stored outcome;
 * conflicting results fail closed; RETRYABLE failures require a
 * continuation; exhaustion keeps the wire result immutable while the
 * scheduler outcome is unambiguous.
 */
@DisplayName("F10: OrchestrationOutcomeService (§16.6)")
class OrchestrationOutcomeServiceTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationOutcomeService outcomeService;

    @Autowired
    private TaskAttemptRepository attemptRepository;
    @Autowired
    private WorkflowTaskRepository taskRepository;
    @Autowired
    private WorkflowRequestRepository requestRepository;

    private WorkflowRequest request;
    private WorkflowTask task;

    @BeforeEach
    void setUp() {
        Project project = data.project().named("outcome").withRepo("https://x.git", "main").create();
        AgentProfile profile = data.agentProfile().named("outcome-profile").create();
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflowOf(project));
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/outcome");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = taskRepository.save(task);
    }

    private ai.myrmec.engine.workflow.Workflow workflowOf(Project project) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("outcome-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }

    private TaskAttempt runningAttempt() {
        TaskAttempt attempt = task.createAttempt(null);
        attempt = attemptRepository.save(attempt);
        // re-load task (attempt creation may touch state)
        task = taskRepository.findById(task.getId()).orElseThrow();
        return attempt;
    }

    private Map<String, Object> result(OrchestrationOutcomeService.OutcomeStatus status,
                                       OrchestrationOutcomeService.RetryDisposition disposition) {
        Map<String, Object> structured = new HashMap<>();
        structured.put("summary", "unit outcome");
        structured.put("workerCalls", 3);
        if (status == OrchestrationOutcomeService.OutcomeStatus.FAILED
                && disposition == OrchestrationOutcomeService.RetryDisposition.RETRYABLE) {
            structured.put("continuation", Map.of(
                    "continuationId", "cont-" + UUID.randomUUID(),
                    "continuationRef", "ref-1",
                    "snapshotTreeHash", "abc",
                    "workspaceRevision", 2,
                    "stateDigest", "digest"));
        }
        return structured;
    }

    // ── COMPLETED ────────────────────────────────────────────

    @Test
    @DisplayName("COMPLETED → attempt COMPLETED/SUCCESS, task COMPLETED/SUCCESS")
    void completedTuple() {
        TaskAttempt attempt = runningAttempt();
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d1",
                OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                OrchestrationOutcomeService.RetryDisposition.NONE,
                null, result(OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                        OrchestrationOutcomeService.RetryDisposition.NONE));

        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.SUCCESS);
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(storedTask.getResult()).isEqualTo(TaskResult.SUCCESS);
        // structured result mirrored to both
        assertThat(stored.getOutput()).containsKey("workerCalls");
        assertThat(storedTask.getOutput()).containsKey("workerCalls");
    }

    // ── FAILED + RETRYABLE with budget ───────────────────────

    @Test
    @DisplayName("FAILED/RETRYABLE with retries remaining resets the task to PENDING")
    void retryableTuple() {
        TaskAttempt attempt = runningAttempt();
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d2",
                OrchestrationOutcomeService.OutcomeStatus.FAILED,
                OrchestrationOutcomeService.RetryDisposition.RETRYABLE,
                "WORKER_FAILED",
                result(OrchestrationOutcomeService.OutcomeStatus.FAILED,
                        OrchestrationOutcomeService.RetryDisposition.RETRYABLE));

        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(stored.getResult()).isEqualTo(TaskResult.FAILURE);
        // task reset to PENDING for the retry; request stays RUNNING
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(storedTask.getResult()).isNull();
        assertThat(storedTask.getAttempt()).isEqualTo(2);
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.RUNNING);
    }

    // ── FAILED + RETRYABLE exhausted ──────────────────────────

    @Test
    @DisplayName("FAILED/RETRYABLE exhausted keeps the wire result, task COMPLETED/FAILURE, request FAILED")
    void retryableExhaustedTuple() {
        TaskAttempt attempt = runningAttempt();
        var structured = result(OrchestrationOutcomeService.OutcomeStatus.FAILED,
                OrchestrationOutcomeService.RetryDisposition.RETRYABLE);
        // Exhaust the budget: maxRetries=1 and this attempt already used it.
        task.setMaxRetries(0);
        taskRepository.save(task);
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d3",
                OrchestrationOutcomeService.OutcomeStatus.FAILED,
                OrchestrationOutcomeService.RetryDisposition.RETRYABLE,
                "WORKER_FAILED", structured);

        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.FAILED);
        // the final Agent result retains its original resultId + errorCode
        assertThat(stored.getErrorCode()).isEqualTo("WORKER_FAILED");
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(storedTask.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.FAILED);
    }

    // ── Terminal FAILED ──────────────────────────────────────

    @Test
    @DisplayName("terminal FAILED → task COMPLETED/FAILURE, request FAILED")
    void terminalFailedTuple() {
        TaskAttempt attempt = runningAttempt();
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d4",
                OrchestrationOutcomeService.OutcomeStatus.FAILED,
                OrchestrationOutcomeService.RetryDisposition.TERMINAL,
                "BUDGET VIOLATION",
                result(OrchestrationOutcomeService.OutcomeStatus.FAILED,
                        OrchestrationOutcomeService.RetryDisposition.TERMINAL));

        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(storedTask.getResult()).isEqualTo(TaskResult.FAILURE);
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.FAILED);
    }

    // ── CANCELLED ─────────────────────────────────────────────

    @Test
    @DisplayName("CANCELLED → attempt ABANDONED, task CANCELLED, request CANCELLED")
    void cancelledTuple() {
        TaskAttempt attempt = runningAttempt();
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d5",
                OrchestrationOutcomeService.OutcomeStatus.CANCELLED,
                OrchestrationOutcomeService.RetryDisposition.NONE,
                null, result(OrchestrationOutcomeService.OutcomeStatus.CANCELLED,
                        OrchestrationOutcomeService.RetryDisposition.NONE));

        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.CANCELLED);
    }

    // ── PAUSED (§16.6 + §17.4 suspension record) ──────────────

    @Test
    @DisplayName("PAUSED → attempt PAUSED, task PAUSED + pauseState=ORCH_REVIEW, request PAUSED")
    void pausedTuple() {
        TaskAttempt attempt = runningAttempt();
        var structured = result(OrchestrationOutcomeService.OutcomeStatus.PAUSED,
                OrchestrationOutcomeService.RetryDisposition.NONE);
        structured.put("suspensionExpiresAt", "2099-01-01T00:00:00Z");
        outcomeService.applyResult(attempt.getId(), UUID.randomUUID(), "d6",
                OrchestrationOutcomeService.OutcomeStatus.PAUSED,
                OrchestrationOutcomeService.RetryDisposition.NONE,
                null, structured);

        TaskAttempt stored = attemptRepository.findById(attempt.getId()).orElseThrow();
        WorkflowTask storedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AttemptStatus.PAUSED);
        assertThat(storedTask.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(storedTask.getPauseState()).isEqualTo("ORCH_REVIEW");
        assertThat(storedTask.getApprovalExpiresAt()).isNotNull();
        assertThat(requestRepository.findById(request.getId()).orElseThrow()
                .getStatus()).isEqualTo(RequestStatus.PAUSED);
    }

    // ── Duplicate / conflicting results ───────────────────────

    @Test
    @DisplayName("the same resultId replays the stored outcome; a different one fails closed")
    void duplicateSuppression() {
        TaskAttempt attempt = runningAttempt();
        UUID resultId = UUID.randomUUID();
        var structured = result(OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                OrchestrationOutcomeService.RetryDisposition.NONE);

        var first = outcomeService.applyResult(attempt.getId(), resultId, "d7",
                OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                OrchestrationOutcomeService.RetryDisposition.NONE, null, structured);
        assertThat(first.replay()).isFalse();

        // Exact replay: same resultId + digest → returns the stored outcome.
        var replay = outcomeService.applyResult(attempt.getId(), resultId, "d7",
                OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                OrchestrationOutcomeService.RetryDisposition.NONE, null, structured);
        assertThat(replay.replay()).isTrue();

        // A DIFFERENT result for the same dispatch fails closed.
        assertThatThrownBy(() -> outcomeService.applyResult(attempt.getId(),
                UUID.randomUUID(), "d8",
                OrchestrationOutcomeService.OutcomeStatus.COMPLETED,
                OrchestrationOutcomeService.RetryDisposition.NONE, null, structured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting terminal result");
    }

    // ── RETRYABLE without continuation fails closed ────────────

    @Test
    @DisplayName("FAILED/RETRYABLE without a continuation record is rejected")
    void retryableRequiresContinuation() {
        TaskAttempt attempt = runningAttempt();
        assertThatThrownBy(() -> outcomeService.applyResult(attempt.getId(),
                UUID.randomUUID(), "d9",
                OrchestrationOutcomeService.OutcomeStatus.FAILED,
                OrchestrationOutcomeService.RetryDisposition.RETRYABLE,
                "WORKER_FAILED", Map.of("summary", "no continuation")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a continuation");
    }
}