// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one orchestration terminal result transactionally under the task
 * attempt row lock (design §16.6). Duplicate {@code resultId} delivery is
 * suppressed (the stored outcome returns); a different result for the same
 * dispatch fails closed. Every status tuple is exact:
 *
 * <table border="1">
 * <tr><th>Runner outcome</th><th>Attempt</th><th>Task</th><th>Request</th></tr>
 * <tr><td>COMPLETED</td><td>COMPLETED/SUCCESS</td><td>COMPLETED/SUCCESS</td>
 *     <td>progress dependants; complete when none remain</td></tr>
 * <tr><td>FAILED + RETRYABLE, attempts remain</td><td>FAILED/FAILURE</td>
 *     <td>Reset PENDING, clear dispatch fields</td><td>stays RUNNING</td></tr>
 * <tr><td>FAILED + RETRYABLE, exhausted</td><td>FAILED/FAILURE</td>
 *     <td>COMPLETED/FAILURE, retain result</td><td>FAILED, release</td></tr>
 * <tr><td>FAILED terminal</td><td>FAILED/FAILURE</td><td>COMPLETED/FAILURE</td>
 *     <td>FAILED, release</td></tr>
 * <tr><td>CANCELLED</td><td>ABANDONED</td><td>CANCELLED</td><td>stays CANCELLED</td></tr>
 * <tr><td>PAUSED</td><td>PAUSED</td><td>PAUSED + pauseState=ORCH_REVIEW</td>
 *     <td>PAUSED, retain checkout</td></tr>
 * </table>
 *
 * <p>Engine task failure remains {@code TaskStatus.COMPLETED} +
 * {@code TaskResult.FAILURE} — no new {@code TaskStatus.FAILED} is
 * introduced. The complete structured result is saved in attempt output
 * and mirrored to task output for every outcome.</p>
 */
@Service
@Slf4j
public class OrchestrationOutcomeService {

    private final TaskAttemptRepository attemptRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    /** Feature 10: the §16.6 progression — completed tasks unblock
     * dependants and complete the request; terminal failure fails it. */
    private final WorkflowProgressionService progressionService;
    /** Pause-time coordinator pinning (design D11): resolves the paused
     * attempt's serving host from its execution session. */
    private final ai.myrmec.engine.inference.execution.SessionExecutionRepository sessionExecutionRepository;
    private final ai.myrmec.engine.inference.SessionRepository sessionRepository;
    private final ai.myrmec.engine.agent.AgentHostInstanceRepository agentHostInstanceRepository;
    private final OrchestrationAffinityResolver affinityResolver;

    public OrchestrationOutcomeService(
            TaskAttemptRepository attemptRepository,
            WorkflowTaskRepository taskRepository,
            WorkflowRequestRepository requestRepository,
            WorkflowProgressionService progressionService,
            ai.myrmec.engine.inference.execution.SessionExecutionRepository sessionExecutionRepository,
            ai.myrmec.engine.inference.SessionRepository sessionRepository,
            ai.myrmec.engine.agent.AgentHostInstanceRepository agentHostInstanceRepository,
            OrchestrationAffinityResolver affinityResolver) {
        this.attemptRepository = attemptRepository;
        this.taskRepository = taskRepository;
        this.requestRepository = requestRepository;
        this.progressionService = progressionService;
        this.sessionExecutionRepository = sessionExecutionRepository;
        this.sessionRepository = sessionRepository;
        this.agentHostInstanceRepository = agentHostInstanceRepository;
        this.affinityResolver = affinityResolver;
    }

    /** The §7.3 status union mirrored from the runner result. */
    public enum OutcomeStatus { COMPLETED, FAILED, CANCELLED, PAUSED }

    /** The §7.3 retry disposition union. */
    public enum RetryDisposition { NONE, RETRYABLE, TERMINAL }

    /**
     * Apply one terminal result. The {@code resultId} uniqueness guard and
     * digest check run under the attempt row lock (loaded pessimistically).
     *
     * @return the applied outcome — or the stored one when this frame is a
     *         duplicate replay of the same result
     */
    @Transactional
    public AppliedOutcome applyResult(
            UUID dispatchId,
            UUID resultId,
            String resultDigest,
            OutcomeStatus status,
            RetryDisposition retryDisposition,
            String errorCode,
            Map<String, Object> structuredResult) {

        TaskAttempt attempt = attemptRepository.findWithLockById(dispatchId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown dispatch: " + dispatchId));

        // Duplicate suppression: the same terminal frame returns the stored
        // outcome; a different result for the same dispatch fails closed.
        if (attempt.getOrchestrationResultId() != null) {
            if (attempt.getOrchestrationResultId().equals(resultId)
                    && attempt.getOrchestrationResultDigest().equals(resultDigest)) {
                log.info("Result {} replay for dispatch {} — returning stored outcome",
                        resultId, dispatchId);
                return AppliedOutcome.replayOf(status);
            }
            throw new IllegalStateException(
                    "Conflicting terminal result for dispatch " + dispatchId
                            + " (stored " + attempt.getOrchestrationResultId()
                            + ", incoming " + resultId + ")");
        }

        // §16.6: RETRYABLE failures require a valid continuation; without
        // one the result cannot be safely retried (fail closed).
        if (status == OutcomeStatus.FAILED
                && retryDisposition == RetryDisposition.RETRYABLE
                && !hasContinuation(structuredResult)) {
            throw new IllegalStateException(
                    "FAILED/RETRYABLE result without a continuation record");
        }

        WorkflowTask task = attempt.getTask();
        WorkflowRequest request = task.getRequest();

        // Stamp the result identity — the wire result stays immutable.
        attempt.setOrchestrationResultId(resultId);
        attempt.setOrchestrationResultDigest(resultDigest);

        // The complete structured result is saved in attempt output and
        // mirrored to task output for every outcome — never prose.
        attempt.setOutput(structuredResult);
        task.setOutput(structuredResult);

        switch (status) {
            case COMPLETED -> applyCompleted(attempt, task, request);
            case FAILED -> applyFailed(attempt, task, request,
                    retryDisposition, errorCode, structuredResult);
            case CANCELLED -> applyCancelled(attempt, task, request);
            case PAUSED -> applyPaused(attempt, task, request, structuredResult);
        }

        // §16.6: COMPLETED progresses dependants (and completes the
        // request when nothing remains); terminal FAILED fails it.
        // RETRYABLE-failure resets keep the request RUNNING — no
        // progression pass. The progression runs on the just-saved rows
        // inside this transaction so the state is consistent.
        if (status == OutcomeStatus.COMPLETED) {
            taskRepository.save(task);
            progressionService.onTaskCompleted(task);
        } else if (status == OutcomeStatus.FAILED
                && task.getStatus() == TaskStatus.COMPLETED
                && task.getResult() == TaskResult.FAILURE) {
            taskRepository.save(task);
            progressionService.onTaskFailed(task);
        }

        attemptRepository.save(attempt);
        taskRepository.save(task);
        requestRepository.save(request);
        // Unified session execution (D9/D4/D5): the run-lease machinery is
        // deleted - after the terminal frame the engine closes the session
        // and the host releases the task-scoped checkout on session close
        // (protocol 9). No release frame exists to send.
        log.info("Applied orchestration outcome {} (dispatch {}, task {}, request {})",
                status, dispatchId, task.getId(), request.getId());
        return AppliedOutcome.applied(status);
    }

    private void applyCompleted(TaskAttempt attempt, WorkflowTask task,
                                WorkflowRequest request) {
        attempt.markSuccess(attempt.getOutput());
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.SUCCESS);
        task.setCompletedAt(Instant.now());
        // Request completion/progression handled by the existing
        // WorkflowProgressionService onTaskCompleted path. §17.4: a
        // resumed attempt (after HITL approve) proves the run continues —
        // a PAUSED request returns to RUNNING so progression can complete
        // it when nothing remains.
        if (request.getStatus() == RequestStatus.PENDING
                || request.getStatus() == RequestStatus.PAUSED) {
            request.setStatus(RequestStatus.RUNNING);
            if (request.getStartedAt() == null) request.setStartedAt(Instant.now());
        }
    }

    private void applyFailed(TaskAttempt attempt, WorkflowTask task,
                            WorkflowRequest request, RetryDisposition disposition,
                            String errorCode, Map<String, Object> structuredResult) {
        attempt.markFailed(
                String.valueOf(structuredResult.getOrDefault("summary", "orchestration failed")),
                errorCode);
        boolean retryable = disposition == RetryDisposition.RETRYABLE;
        int usedRetries = countRetryableAttempts(task.getId());
        int maxRetries = task.getMaxRetries() == null ? 0 : task.getMaxRetries();

        if (retryable && usedRetries <= maxRetries) {
            // Reset to PENDING; preserve checkout, continuation, affinity.
            attempt.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.PENDING);
            task.setResult(null);
            task.setErrorMessage(null);
            task.setStartedAt(null);
            task.setCompletedAt(null);
            task.setAgentInstance(null);
            task.setAttempt(task.getAttempt() + 1);
            // §16.6: retryable failure consumes one retry and applies the
            // step's bounded exponential backoff.
            task.setNextEligibleAt(Instant.now());
            // §17.4: a retryable failure on a resumed run also returns a
            // PAUSED request to RUNNING (the retry continues the run).
            if (request.getStatus() == RequestStatus.PENDING
                    || request.getStatus() == RequestStatus.PAUSED) {
                request.setStatus(RequestStatus.RUNNING);
            }
        } else {
            // Exhausted or terminal: the runner result keeps its original
            // resultId/disposition/errorCode; the scheduler outcome is
            // unambiguous — task COMPLETED/FAILURE, request FAILED.
            attempt.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.COMPLETED);
            task.setResult(TaskResult.FAILURE);
            task.setCompletedAt(Instant.now());
            task.setNextEligibleAt(null);
            request.setStatus(RequestStatus.FAILED);
            if (request.getCompletedAt() == null) {
                request.setCompletedAt(Instant.now());
            }
        }
    }

    private void applyCancelled(TaskAttempt attempt, WorkflowTask task,
                               WorkflowRequest request) {
        attempt.markAbandoned("Cancelled");
        task.setStatus(TaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        request.setStatus(RequestStatus.CANCELLED);
        if (request.getCompletedAt() == null) {
            request.setCompletedAt(Instant.now());
        }
    }

    private void applyPaused(TaskAttempt attempt, WorkflowTask task,
                            WorkflowRequest request, Map<String, Object> structuredResult) {
        // The attempt keeps its signed output immutable and enters PAUSED.
        attempt.setStatus(AttemptStatus.PAUSED);
        attempt.setCompletedAt(null);
        task.setStatus(TaskStatus.PAUSED);
        task.setPauseState("ORCH_REVIEW");
        task.setPausedAt(Instant.now());
        // Task-scoped workspaces (design D11): the ONLY host-local state a
        // paused run holds is the SAME_HOST continuation manifest on the
        // host that paused. The coordinator pin is therefore recorded AT
        // PAUSE (here), not at first dispatch - a run with no paused
        // continuation has nothing host-local to preserve and dispatches
        // freely to any live host. The pause-time pin resolves the serving
        // host from the attempt's execution session.
        pinCoordinatorOnPause(request.getId(), attempt);
        // The unified protocol 8.6 suspension record drives the approval
        // deadline: the paused payload nests expiry under
        // suspension.expiresAt - a flat key never arrives on the wire.
        // Null-safe: a paused result without a suspension record leaves
        // the approval deadline unset.
        if (structuredResult.get("suspension") instanceof Map<?, ?> suspension
                && suspension.get("expiresAt") != null) {
            task.setApprovalExpiresAt(Instant.parse(suspension.get("expiresAt").toString()));
        }
        request.setStatus(RequestStatus.PAUSED);
    }

    private boolean hasContinuation(Map<String, Object> structuredResult) {
        return structuredResult != null && structuredResult.get("continuation") != null;
    }

    /**
     * Design D11 (pause-time pinning): a PAUSED run's only host-local state
     * is the SAME_HOST continuation manifest on the host that paused - so
     * the coordinator pin is recorded HERE (at pause), never at first
     * dispatch. The serving host is resolved through the attempt's
     * execution -> session -> host instance -> agent host chain; a
     * resolution failure logs and leaves the run unpinned (the resume
     * dispatch then selects freely - fail-open, the decision enrichment
     * carries the continuation identity either way).
     */
    private void pinCoordinatorOnPause(UUID requestId, TaskAttempt attempt) {
        try {
            UUID servingHostId = sessionExecutionRepository
                    .findByDispatchId(attempt.getId())
                    .map(exec -> sessionRepository.findById(exec.getSessionId())
                            .map(ai.myrmec.engine.inference.Session::getHostInstanceId)
                            .orElse(null))
                    .flatMap(instanceId -> instanceId == null
                            ? java.util.Optional.<UUID>empty()
                            : agentHostInstanceRepository.findById(instanceId)
                                    .map(ai.myrmec.engine.agent.AgentHostInstance::getAgentHostId))
                    .orElse(null);
            if (servingHostId != null) {
                affinityResolver.recordCoordinator(requestId, null, servingHostId);
            } else {
                log.warn("Run {} pause-time coordinator pin skipped - serving host unresolvable",
                        requestId);
            }
        } catch (Exception e) {
            log.warn("Run {} pause-time coordinator pin failed: {}",
                    requestId, e.getMessage());
        }
    }

    private int countRetryableAttempts(UUID taskId) {
        return attemptRepository.countRetryableAttempts(taskId);
    }

    /** What this call did. */
    public record AppliedOutcome(OutcomeStatus status, boolean replay) {
        static AppliedOutcome applied(OutcomeStatus status) {
            return new AppliedOutcome(status, false);
        }
        static AppliedOutcome replayOf(OutcomeStatus status) {
            return new AppliedOutcome(status, true);
        }
    }
}