// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket;

import ai.myrmec.engine.workflow.ExecutionApprovalService;
import ai.myrmec.engine.workflow.OrchestrationDispatchRelay;
import ai.myrmec.engine.workflow.OrchestrationEventIngestionService;
import ai.myrmec.engine.workflow.OrchestrationOutcomeService;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.WorkspaceReleaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Inbound handler for the orchestration frames (design §16.3, Feature 10).
 *
 * <p>Every frame is treated as untrusted data: the dispatch correlation
 * is validated against engine rows (the dispatchId is the attempt UUID),
 * the authenticated agent instance must own that attempt, and the
 * deterministic ids/digests are checked by the underlying services:</p>
 * <ul>
 *   <li>{@code inference.accept} carrying a dispatchId → the
 *       resend-until-accept relay flips the dispatch row ACCEPTED under a
 *       lock (digest-conflict fails closed).</li>
 *   <li>{@code orchestration.event} → idempotent ingestion into
 *       {@code execution_events} keyed by the Agent's deterministic
 *       eventId (replay no-op; slot conflict rejected).</li>
 *   <li>{@code orchestration.approval_requested} → durable HITL proposal
 *       persisted on the task's existing approval fields.</li>
 *   <li>{@code orchestration.result} → exactly-once outcome application
 *       under the attempt row lock (§16.6).</li>
 *   <li>{@code host.announce} carrying a release acknowledgement →
 *       idempotent {@code WORKSPACE_RELEASED} evidence (§16.5).</li>
 * </ul>
 *
 * <p>Malformed frames are audited and dropped — they never mutate state.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundOrchestrationHandler {

    private final OrchestrationDispatchRelay dispatchRelay;
    private final OrchestrationEventIngestionService eventIngestionService;
    private final OrchestrationOutcomeService outcomeService;
    private final WorkspaceReleaseService workspaceReleaseService;
    private final ExecutionApprovalService executionApprovalService;
    private final TaskAttemptRepository attemptRepository;
    private final ObjectMapper objectMapper;

    /**
     * {@code orchestration.approval_requested} — durable HITL proposal
     * (§17.4). The adapter stores one pending orchestration approval on the
     * existing WorkflowTask approval fields: bounded, metadata-only payload
     * (approval request id, action type, risk, digests, generation) with no
     * source-bearing data.
     */
    public void onOrchestrationApprovalRequested(UUID agentInstanceId, JsonNode payload) {
        UUID dispatchId = uuidOf(payload.path("dispatch"), "dispatchId");
        UUID approvalRequestId = uuidOf(payload, "approvalRequestId");
        if (dispatchId == null || approvalRequestId == null) {
            log.warn("orchestration.approval_requested from agent {} missing identity — dropped",
                    agentInstanceId);
            return;
        }
        // Resolve the task from the dispatch correlation (§16.3: the
        // dispatch is the attempt — validated from engine rows).
        var attempt = attemptRepository.findById(dispatchId).orElse(null);
        if (attempt == null) {
            log.warn("orchestration.approval_requested for unknown dispatch {} — dropped",
                    dispatchId);
            return;
        }
        String expiresAt = textOf(payload, "expiresAt");
        java.time.Instant expiry = null;
        if (expiresAt != null) {
            try {
                expiry = java.time.Instant.parse(expiresAt);
            } catch (Exception e) {
                log.warn("approval_requested {} malformed expiry — treating as none",
                        approvalRequestId);
            }
        }
        Map<String, Object> boundedPayload = objectMapper.convertValue(
                payload,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        executionApprovalService.requestApproval(
                attempt.getTask().getId(),
                "Orchestration approval " + approvalRequestId,
                boundedPayload,
                expiry);
        log.info("Orchestration approval {} persisted for task {} (dispatch {})",
                approvalRequestId, attempt.getTask().getId(), dispatchId);
    }

    /**
     * {@code inference.accept} for an orchestration dispatch: the Agent
     * durably admitted the exact assignment bytes. Replay returns the same
     * acknowledgement; a digest conflict fails closed.
     */
    public void onInferenceAccept(UUID agentInstanceId, JsonNode payload) {
        UUID dispatchId = uuidOf(payload, "dispatchId");
        String digest = textOf(payload, "assignmentDigest");
        if (dispatchId == null || digest == null) {
            log.warn("inference.accept from agent {} missing dispatchId/assignmentDigest — dropped",
                    agentInstanceId);
            return;
        }
        boolean admitted = dispatchRelay.accept(dispatchId, digest);
        if (!admitted) {
            log.warn("inference.accept for dispatch {} REJECTED (conflicting digest) — agent {}",
                    dispatchId, agentInstanceId);
        }
    }

    /**
     * {@code orchestration.event} — ordered, redacted progress metadata.
     */
    public void onOrchestrationEvent(UUID agentInstanceId, JsonNode payload) {
        UUID dispatchId = uuidOf(payload.path("dispatch"), "dispatchId");
        UUID sourceEventId = uuidOf(payload, "eventId");
        if (dispatchId == null || sourceEventId == null) {
            log.warn("orchestration.event from agent {} missing dispatch/eventId — dropped",
                    agentInstanceId);
            return;
        }
        JsonNode sequenceNode = payload.path("sequence");
        if (!sequenceNode.canConvertToLong()) {
            log.warn("orchestration.event {} missing sequence — dropped", sourceEventId);
            return;
        }
        String type = textOf(payload, "type");
        Map<String, Object> envelope = objectMapper.convertValue(
                payload.path("payload"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        try {
            eventIngestionService.ingest(dispatchId, sourceEventId,
                    sequenceNode.asLong(), type, envelope);
        } catch (IllegalStateException conflict) {
            // Conflicting duplicate / slot collision — audited, state intact.
            log.warn("orchestration.event {} rejected: {}", sourceEventId, conflict.getMessage());
        } catch (IllegalArgumentException unknown) {
            // A hostile/buggy event referencing a dispatch with no engine
            // row — audited and dropped, never thrown into the container.
            log.warn("orchestration.event {} for unknown dispatch {} — dropped",
                    sourceEventId, dispatchId);
        }
    }

    /**
     * {@code orchestration.result} — one logical terminal result. Duplicate
     * delivery returns the stored outcome; a conflicting result fails closed.
     */
    public void onOrchestrationResult(UUID agentInstanceId, JsonNode payload) {
        UUID dispatchId = uuidOf(payload.path("dispatch"), "dispatchId");
        UUID resultId = uuidOf(payload, "resultId");
        String digest = textOf(payload, "resultDigest");
        String status = textOf(payload, "status");
        if (dispatchId == null || resultId == null || digest == null || status == null) {
            log.warn("orchestration.result from agent {} missing identity fields — dropped",
                    agentInstanceId);
            return;
        }
        OrchestrationOutcomeService.OutcomeStatus outcomeStatus;
        try {
            outcomeStatus = OrchestrationOutcomeService.OutcomeStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            log.warn("orchestration.result {} unknown status '{}' — dropped", resultId, status);
            return;
        }
        String retryDisposition = textOf(payload, "retryDisposition");
        OrchestrationOutcomeService.RetryDisposition disposition;
        try {
            disposition = retryDisposition == null
                    ? OrchestrationOutcomeService.RetryDisposition.NONE
                    : OrchestrationOutcomeService.RetryDisposition.valueOf(retryDisposition);
        } catch (IllegalArgumentException e) {
            log.warn("orchestration.result {} unknown retryDisposition '{}' — dropped",
                    resultId, retryDisposition);
            return;
        }
        String errorCode = textOf(payload, "errorCode");
        Map<String, Object> structuredResult = objectMapper.convertValue(
                payload,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        try {
            var applied = outcomeService.applyResult(dispatchId, resultId, digest,
                    outcomeStatus, disposition, errorCode, structuredResult);
            log.info("orchestration.result {} for dispatch {} → {} (replay={})",
                    resultId, dispatchId, applied.status(), applied.replay());
        } catch (IllegalStateException conflict) {
            log.error("orchestration.result {} for dispatch {} REJECTED: {}",
                    resultId, dispatchId, conflict.getMessage());
        } catch (IllegalArgumentException unknown) {
            // A hostile/buggy agent referencing a dispatch with no engine
            // row — audited and dropped, never thrown into the container.
            log.warn("orchestration.result {} for unknown dispatch {} — dropped",
                    resultId, dispatchId);
        }
    }

    /**
     * {@code host.announce} carrying a workspace release acknowledgement
     * (§16.5): validate the deterministic ids and flip the run's lease
     * state. The Agent event sink never emits release completion.
     */
    public void onReleaseAcknowledgement(UUID agentInstanceId, JsonNode payload) {
        UUID releaseId = uuidOf(payload, "releaseId");
        UUID acknowledgementId = uuidOf(payload, "acknowledgementId");
        UUID runId = uuidOf(payload, "runId");
        String status = textOf(payload, "status");
        JsonNode generationNode = payload.path("workspaceGeneration");
        String occurredAt = textOf(payload, "occurredAt");
        if (releaseId == null || runId == null || status == null
                || !generationNode.isInt() || occurredAt == null) {
            log.warn("release acknowledgement from agent {} malformed — dropped", agentInstanceId);
            return;
        }
        // §16.5: the acknowledgement id must equal the derived
        // UUIDv5(releaseId:generation:status) — a forged id fails closed.
        UUID expected = ai.myrmec.engine.workflow.OrchestrationIds.acknowledgementId(
                releaseId, generationNode.asInt(), status);
        if (acknowledgementId == null || !acknowledgementId.equals(expected)) {
            log.warn("release acknowledgement {} fails the deterministic id check — dropped",
                    acknowledgementId);
            return;
        }
        try {
            workspaceReleaseService.recordAcknowledgement(
                    runId, releaseId, generationNode.asInt(), status,
                    java.time.Instant.parse(occurredAt));
        } catch (IllegalArgumentException e) {
            log.warn("release acknowledgement for run {} rejected: {}", runId, e.getMessage());
        }
    }

    private static UUID uuidOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return UUID.fromString(value.asText());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (value.isNumber()) {
            try {
                return UUID.fromString(value.asText());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}