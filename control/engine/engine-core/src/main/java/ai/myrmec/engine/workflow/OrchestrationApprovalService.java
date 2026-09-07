// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * OrchestrationApprovalService (design §16.6/§17.4, HITL slice B): the
 * orchestration-aware decision path for {@code pauseState=ORCH_REVIEW}
 * tasks — approve (resume), reject, and expiry, each applying the exact
 * §16.6 tuple under the task row lock.
 *
 * <p>Rejection and expiry are ENGINE-owned terminal transitions: the
 * original PAUSED attempt keeps its signed output immutable, the task
 * becomes {@code COMPLETED/FAILURE} with the decision's error code, no
 * continuation attempt is created, the request is marked FAILED, and the
 * workspace is released. {@code WorkflowTaskPauseService.stop} is
 * deliberately NOT used — it marks a task FAILED where the orchestration
 * tuple requires COMPLETED/FAILURE.</p>
 *
 * <p>Approval validates the stored approval payload digests under the
 * lock (a decision for an older proposal cannot authorize a new one),
 * resets the task to PENDING, and dispatches a fresh continuation
 * attempt carrying the typed decision envelope; affinity is preserved
 * (the coordinator receives the continuation).</p>
 *
 * <p>Approver targeting (§17.4): a workflow orchestration approval is
 * decided by exactly one human — the triggering user
 * ({@code WorkflowRequest.createdBy}). Any other decider is rejected
 * under the lock.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationApprovalService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    private final TaskAttemptRepository attemptRepository;
    private final WorkspaceReleaseOrchestrator releaseOrchestrator;

    /** The decision outcome for one ORCH_REVIEW task. */
    public enum DecisionOutcome { APPROVED, REJECTED, EXPIRED }

    /**
     * Apply one human decision to an ORCH_REVIEW task.
     *
     * @param taskId      the workflow task holding the pending approval
     * @param decision    APPROVED or REJECTED (the REST surface's values)
     * @param deciderId   the deciding user — must be the triggering user
     * @return the applied outcome
     */
    @Transactional
    public DecisionOutcome decide(UUID taskId, String decision, UUID deciderId) {
        WorkflowTask task = taskRepository.findWithLockById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));
        requireTriggeringUser(task, deciderId);
        requireDecidable(task);

        return switch (decision.toUpperCase()) {
            case "APPROVED" -> approve(task, deciderId);
            case "REJECTED" -> reject(task, "APPROVAL_REJECTED");
            default -> throw new IllegalArgumentException(
                    "Invalid orchestration decision: " + decision);
        };
    }

    /**
     * The §16.6 expiry tuple — applied by the sweeper (and reusable here
     * if a decision arrives past expiry).
     */
    @Transactional
    public DecisionOutcome expire(UUID taskId) {
        WorkflowTask task = taskRepository.findWithLockById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));
        requireOrchReview(task);
        return reject(task, "APPROVAL_EXPIRED");
    }

    // ── approve: the resume path ─────────────────────────────────

    private DecisionOutcome approve(WorkflowTask task, UUID deciderId) {
        WorkflowRequest request = task.getRequest();

        // §17.4: validate the stored payload digests under the lock —
        // the approval payload must still carry the suspension identity
        // (approvalRequestId + state digest); a decision for an older
        // proposal cannot authorize a new one.
        Map<String, Object> payload = task.getApprovalPayload();
        if (payload == null || payload.get("approvalRequestId") == null) {
            throw new IllegalStateException(
                    "Task " + task.getId() + " approval payload lost its suspension identity");
        }

        // The §7.3 suspension record from the stored PAUSED result —
        // the continuation identity (id + workspace revision) the typed
        // decision envelope must bind to.
        Map<String, Object> suspension = suspensionOf(task);
        Object continuationId = suspension.get("continuationId");
        if (continuationId == null) {
            throw new IllegalStateException(
                    "Task " + task.getId() + " has no suspension continuation to resume");
        }

        // §16.6 continuation: reset the task to PENDING, clear dispatch
        // timestamps, keep the structured output (the signed PAUSED
        // result stays on the attempt), restore the request to RUNNING.
        // pauseState returns to the NONE baseline (never null — the J3
        // progression gate treats null as "never evaluated" and would
        // re-pause a NONE-mode task after completion).
        task.setApprovalStatus("APPROVED");
        task.setStatus(TaskStatus.PENDING);
        task.setPauseState("NONE");
        task.setPausedAt(null);
        task.setPauseReason(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setAgentInstance(null);
        task.setNextEligibleAt(null);
        // One fresh continuation attempt ordinal — the attempt counter
        // advances so the dispatcher creates a NEW attempt row.
        task.setAttempt(task.getAttempt() + 1);

        // §17.4: enrich the stored payload with the decision evidence —
        // the §16.2 assembler attaches the typed continuation (and this
        // decision) to the resume attempt from this payload.
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(
                payload != null ? payload : Map.of());
        enriched.put("decisionStatus", "APPROVED");
        enriched.put("decidedAt", Instant.now().toString());
        enriched.put("decidedBy", String.valueOf(deciderId));
        enriched.put("decisionId", OrchestrationIds.uuidV5(OrchestrationIds.ORCHESTRATION_SCHEDULING_NS,
                "decision:" + task.getId() + ":" + task.getAttempt()).toString());
        enriched.put("previousDispatchId", previousDispatchIdOf(task));
        // The §7.3 suspension identity the assembler's decision
        // envelope binds to (§7 ApprovalDecision fields).
        enriched.put("suspensionContinuationId", String.valueOf(continuationId));
        enriched.put("stateDigest", String.valueOf(suspension.get("stateDigest")));
        enriched.put("snapshotTreeHash", String.valueOf(suspension.get("snapshotTreeHash")));
        enriched.put("workspaceGeneration", suspension.get("workspaceRevision"));
        enriched.put("actionDigest", actionDigestOf(payload));
        enriched.put("approvalRequestId", String.valueOf(payload.get("approvalRequestId")));
        enriched.put("expiresAt", String.valueOf(payload.get("expiresAt")));
        task.setApprovalPayload(enriched);
        taskRepository.save(task);

        if (request.getStatus() == RequestStatus.PAUSED) {
            request.setStatus(RequestStatus.RUNNING);
            requestRepository.save(request);
        }

        log.info("Orchestration approval APPROVED for task {} — continuation attempt {} "
                        + "dispatches to the coordinator",
                task.getId(), task.getAttempt());
        // The dispatcher's scheduled pass picks the PENDING task up and
        // creates the continuation attempt (affinity pins it to the
        // coordinator). The §16.2 assembler attaches the continuation
        // directive from the stored suspension on the new attempt.
        return DecisionOutcome.APPROVED;
    }

    // ── reject/expiry: the engine-owned terminal tuple ────────────

    private DecisionOutcome reject(WorkflowTask task, String errorCode) {
        WorkflowRequest request = task.getRequest();

        // §16.6: the original attempt keeps its PAUSED state and its
        // signed output immutable — only the task/request tuple moves.
        task.setApprovalStatus("APPROVAL_REJECTED".equals(errorCode) ? "REJECTED" : "EXPIRED");
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.FAILURE);
        task.setErrorMessage(errorCode);
        task.setPauseState("NONE");
        task.setPausedAt(null);
        task.setPauseReason(null);
        task.setCompletedAt(Instant.now());
        task.setNextEligibleAt(null);
        taskRepository.save(task);

        // The request fails; no continuation attempt; dependants never
        // progress (the failure progression path handles the request).
        request.setStatus(RequestStatus.FAILED);
        if (request.getCompletedAt() == null) {
            request.setCompletedAt(Instant.now());
        }
        requestRepository.save(request);

        // §16.5: release the run's workspace after the terminal state.
        try {
            releaseOrchestrator.releaseIfOrchestrated(request);
        } catch (Exception e) {
            log.warn("Post-rejection release for run {} failed: {}",
                    request.getId(), e.getMessage());
        }

        log.info("Orchestration approval {} for task {} (run {}) — terminal tuple applied",
                errorCode, task.getId(), request.getId());
        return "APPROVAL_REJECTED".equals(errorCode)
                ? DecisionOutcome.REJECTED : DecisionOutcome.EXPIRED;
    }

    // ── guards ───────────────────────────────────────────────────

    /**
     * The §7.3 suspension record from the stored PAUSED structured
     * result (mirrored to task output by the outcome path) — never
     * prose; carries the continuation identity for the resume.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> suspensionOf(WorkflowTask task) {
        Map<String, Object> output = task.getOutput();
        if (output != null && output.get("suspension") instanceof Map<?, ?> suspension) {
            return (Map<String, Object>) suspension;
        }
        throw new IllegalStateException(
                "Task " + task.getId() + " PAUSED result carries no suspension record");
    }

    /** The pending action's digest from the stored approval payload. */
    private String actionDigestOf(Map<String, Object> payload) {
        if (payload.get("action") instanceof Map<?, ?> action
                && action.get("digest") != null) {
            return String.valueOf(action.get("digest"));
        }
        throw new IllegalStateException(
                "Task approval payload lost its action digest");
    }

    /**
     * The suspended dispatch this approval continues — the prior
     * attempt id (§16.2: dispatchId == attempt UUID in V1). The resume
     * assignment's {@code ContinuationDirective.previousDispatchId}
     * and the Agent's §7 validation both bind to it.
     */
    private String previousDispatchIdOf(WorkflowTask task) {
        TaskAttempt prior = task.getCurrentAttempt();
        if (prior == null || prior.getId() == null) {
            // The FK may not be persisted on the task row; the latest
            // attempt by ordinal is the same dispatch in V1.
            prior = attemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(task.getId())
                    .orElse(null);
        }
        if (prior != null && prior.getId() != null) {
            return prior.getId().toString();
        }
        throw new IllegalStateException(
                "Task " + task.getId() + " has no prior attempt to continue");
    }

    /**
     * A decidable task: in ORCH_REVIEW with a PENDING approval. A task
     * whose review already completed (pause cleared, decided status)
     * reports already-decided; anything else is not an orchestration
     * review at all.
     */
    private void requireDecidable(WorkflowTask task) {
        String approval = task.getApprovalStatus();
        // A decided approval row (APPROVED/REJECTED/EXPIRED) is refused
        // first — pauseState may already be cleared by that decision.
        if (approval != null && !"PENDING".equals(approval)
                && ("APPROVED".equals(approval) || "REJECTED".equals(approval)
                        || "EXPIRED".equals(approval))) {
            throw new IllegalStateException(
                    "Task " + task.getId() + " approval is already decided ("
                            + approval + ")");
        }
        if ("ORCH_REVIEW".equals(task.getPauseState())) {
            return;
        }
        throw new IllegalStateException(
                "Task " + task.getId() + " is not an orchestration review task "
                        + "(pauseState=" + task.getPauseState() + ")");
    }

    private void requireOrchReview(WorkflowTask task) {
        if (!"ORCH_REVIEW".equals(task.getPauseState())) {
            throw new IllegalStateException(
                    "Task " + task.getId() + " is not an orchestration review task "
                            + "(pauseState=" + task.getPauseState() + ")");
        }
    }

    private void requirePendingApproval(WorkflowTask task) {
        if (!"PENDING".equals(task.getApprovalStatus())) {
            throw new IllegalStateException(
                    "Task " + task.getId() + " approval is already decided ("
                            + task.getApprovalStatus() + ")");
        }
    }

    /**
     * §17.4 approver targeting: exactly one human — the triggering user.
     * Decisions from any other user are rejected under the task row lock.
     */
    private void requireTriggeringUser(WorkflowTask task, UUID deciderId) {
        WorkflowRequest request = task.getRequest();
        UUID triggerer = request.getCreatedBy() != null
                ? request.getCreatedBy().getId() : null;
        if (triggerer == null || !triggerer.equals(deciderId)) {
            throw new IllegalStateException(
                    "Orchestration approvals are decided by the triggering user only "
                            + "(task " + task.getId() + ")");
        }
    }
}