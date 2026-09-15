// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.security.scan.SecretLeakService;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.websocket.host.payload.ExecutionApprovalRequestedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionEventPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionPausedPayload;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.ApprovalRequestPayload;
import ai.myrmec.engine.workflow.ExecutionApprovalService;
import ai.myrmec.engine.workflow.OrchestrationEventIngestionService;
import ai.myrmec.engine.workflow.OrchestrationIds;
import ai.myrmec.engine.workflow.OrchestrationOutcomeService;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Host→engine execution outcome bridging (§16 mapping): the SAME downstream
 * sinks the legacy adapters use, so Plan 8's cutover is a path switch.
 * Conversation: ASSISTANT row + SSE fan-out + quota + secret-leak scan
 * (secret scan runs via SecretLeakService like InboundInferenceHandler).
 * Orchestration: OrchestrationEventIngestionService (events) and
 * OrchestrationOutcomeService.applyResult (terminal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionBridge {

    private final ConversationService conversationService;
    private final ConversationStreamBroker conversationStreamBroker;
    private final SecretLeakService secretLeakService;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final OrchestrationEventIngestionService eventIngestionService;
    private final OrchestrationOutcomeService outcomeService;
    private final ExecutionApprovalService executionApprovalService;
    private final TaskAttemptRepository attemptRepository;
    private final ai.myrmec.engine.workflow.TaskAttemptService taskAttemptService;
    private final ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    /**
     * Conversation complete: mirror InboundInferenceHandler.handleConversationComplete.
     * Secret scan → persist ASSISTANT row → quota recording → enriched message.complete broadcast.
     */
    @Transactional
    public void onConversationComplete(UUID conversationId, UUID projectId, UUID agentId,
                                       ExecutionCompletePayload payload) {
        if (payload == null || payload.result() == null) {
            return;
        }
        String text = payload.result().content();
        if (text == null || text.isBlank()) {
            return;
        }

        SecretLeakService.Result scan = secretLeakService.inspectOutbound(text, conversationId, null);
        if (scan.isBlocked()) {
            log.warn("BLOCKED execution.complete (conversation {}) — secret leak detected", conversationId);
            return;
        }
        String safeText = scan.getText();

        ConversationMessage saved = null;
        try {
            saved = conversationService.appendMessage(
                    conversationId, ConversationMessage.Role.ASSISTANT, safeText, null, agentId);
        } catch (Exception e) {
            log.error("Failed to persist assistant message for conv {}: {}", conversationId, e.getMessage(), e);
        }

        if (saved != null && payload.usage() != null && payload.usage().modelId() != null) {
            saved.setModelCode(payload.usage().modelId());
            saved.setTokenCount(intTokenCount(payload.usage().outputTokens())
                    + intTokenCount(payload.usage().inputTokens()));
        }

        Long totalTokens = payload.usage() == null ? null
                : longTokenCount(payload.usage().outputTokens()) + longTokenCount(payload.usage().inputTokens());
        if (totalTokens != null && totalTokens > 0) {
            recordTokenConsumption(conversationId, projectId, totalTokens);
        }

        if (saved != null) {
            try {
                String mcFrame = objectMapper.writeValueAsString(
                        WebSocketMessage.of(MessageType.MESSAGE_COMPLETE, buildHistoryEnvelope(saved)));
                conversationStreamBroker.broadcast(conversationId, mcFrame);
                log.info("Bridged execution.complete to SSE viewers for conv {} (seq {})",
                        conversationId, saved.getSequenceNo());
            } catch (Exception ex) {
                log.warn("Failed to build message.complete bridge for conv {}: {}", conversationId, ex.getMessage());
            }
        }
    }

    /**
     * Conversation failure: broadcast the raw failure frame to viewers.
     * Mirrors InboundInferenceHandler.onInferenceFailed conversation branch.
     */
    public void onConversationFailure(UUID conversationId, String rawFrame) {
        if (conversationId == null || rawFrame == null) {
            return;
        }
        conversationStreamBroker.broadcast(conversationId, rawFrame);
        log.debug("Bridged execution.failed conversation frame for conv {}", conversationId);
    }

    /**
     * Conversation pause: §8.6/§8.7 — append approval request row carrying the
     * pending action, broadcast approval envelope + bridged message.complete.
     * The caller (handler) owns session close.
     */
    @Transactional
    public void onConversationPaused(UUID conversationId, UUID agentId, ExecutionPausedPayload payload) {
        if (payload == null || payload.conversationContinuation() == null) {
            return;
        }
        String approvalRequestId = payload.conversationContinuation().approvalRequestId();
        String pendingActionId = payload.conversationContinuation().pendingActionId();
        String digest = payload.conversationContinuation().pendingActionDigest();
        if (approvalRequestId == null || pendingActionId == null) {
            log.warn("Discarding execution.paused conversation continuation — missing identity fields");
            return;
        }

        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("approvalRequestId", approvalRequestId);
        marker.put("pendingActionId", pendingActionId);
        marker.put("pendingActionDigest", digest);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(marker);
        } catch (Exception e) {
            payloadJson = "{}";
        }

        ConversationMessage persisted;
        try {
            Instant expiresAt = payload.suspension() == null ? null : payload.suspension().expiresAt();
            persisted = conversationService.appendApprovalRequest(
                    conversationId, agentId,
                    "Approval required before continuing execution: " + pendingActionId,
                    payloadJson, expiresAt);
        } catch (Exception e) {
            log.warn("Failed to persist approval request for conv {}: {}", conversationId, e.getMessage(), e);
            return;
        }

        ApprovalRequestPayload broadcast = ApprovalRequestPayload.builder()
                .conversationId(conversationId)
                .clientRequestId(UUID.fromString(approvalRequestId))
                .messageId(persisted.getId())
                .content(persisted.getContent())
                .payloadJson(persisted.getPayloadJson())
                .expiresAt(persisted.getExpiresAt())
                .sequenceNo(persisted.getSequenceNo())
                .build();
        try {
            String json = objectMapper.writeValueAsString(
                    WebSocketMessage.of(MessageType.APPROVAL_REQUEST, broadcast));
            conversationStreamBroker.broadcast(conversationId, json);
        } catch (Exception e) {
            log.warn("Failed to serialise approval.request envelope for conv {}: {}", conversationId, e.getMessage());
        }

        try {
            String mcFrame = objectMapper.writeValueAsString(
                    WebSocketMessage.of(MessageType.MESSAGE_COMPLETE, buildHistoryEnvelope(persisted)));
            conversationStreamBroker.broadcast(conversationId, mcFrame);
            log.info("Bridged execution.paused approval to message.complete for conv {} (seq {})",
                    conversationId, persisted.getSequenceNo());
        } catch (Exception e) {
            log.warn("Failed to build message.complete bridge for approval conv {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Ordinary (non-orchestration) workflow completion: mirror
     * InboundInferenceHandler.handleWorkflowComplete — the attempt's content /
     * usage output folds into the task-attempt sink, which completes the task
     * row and drives workflow progression.
     */
    @Transactional
    public void onWorkflowComplete(UUID attemptId, ExecutionCompletePayload payload) {
        if (payload == null || payload.result() == null) {
            return;
        }
        if (payload.result().content() == null || payload.result().content().isBlank()) {
            return;
        }
        Map<String, Object> output = new HashMap<>();
        output.put("content", payload.result().content());
        if (payload.result().structured() != null) {
            output.put("structured", payload.result().structured());
        }
        if (payload.usage() != null) {
            if (payload.usage().modelId() != null) output.put("modelCode", payload.usage().modelId());
            Long tokens = longTokenCount(payload.usage().inputTokens())
                    + longTokenCount(payload.usage().outputTokens());
            if (tokens > 0) output.put("tokenCount", tokens);
        }
        try {
            taskAttemptService.completeSuccess(attemptId, output);
        } catch (Exception e) {
            log.error("Failed to complete task attempt {}: {}", attemptId, e.getMessage(), e);
            try {
                taskAttemptService.completeFailed(attemptId,
                        "Inference completed but result could not be saved: " + e.getMessage(),
                        "RESULT_PERSIST_ERROR");
            } catch (Exception e2) {
                log.error("Failed to mark attempt {} as failed after completion error: {}",
                        attemptId, e2.getMessage(), e2);
            }
        }
    }

    /**
     * Ordinary workflow failure: mirror InboundInferenceHandler.onInferenceFailed
     * — the attempt fails with the agent's error code, honouring a retry hint.
     */
    @Transactional
    public void onWorkflowFailure(UUID attemptId, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        Object error = payload.get("error");
        String code = "WORKFLOW_EXECUTION_FAILED";
        String message = "Workflow execution failed";
        Integer retryAfterSeconds = null;
        if (error instanceof Map<?, ?> errorMap) {
            if (errorMap.get("code") != null) code = String.valueOf(errorMap.get("code"));
            if (errorMap.get("message") != null) message = String.valueOf(errorMap.get("message"));
            Object retryAfter = errorMap.get("retryAfterSeconds");
            if (retryAfter instanceof Number n) {
                retryAfterSeconds = n.intValue();
            }
        }
        try {
            taskAttemptService.completeFailed(attemptId, message, code, retryAfterSeconds);
        } catch (Exception e) {
            log.error("Failed to record task failure for attempt {}: {}", attemptId, e.getMessage(), e);
        }
    }

    /** Ordinary workflow cancellation: the attempt is abandoned, the task cancelled. */
    @Transactional
    public void onWorkflowCancelled(UUID attemptId) {
        try {
            taskAttemptService.markAbandoned(attemptId, "Cancelled");
        } catch (Exception e) {
            log.warn("Failed to abandon attempt {} on cancellation: {}", attemptId, e.getMessage());
        }
        attemptRepository.findById(attemptId).ifPresent(attempt -> {
            var task = attempt.getTask();
            if (task != null && task.getStatus() != ai.myrmec.engine.workflow.TaskStatus.CANCELLED) {
                task.setStatus(ai.myrmec.engine.workflow.TaskStatus.CANCELLED);
                task.setCompletedAt(Instant.now());
                workflowTaskRepository.save(task);
            }
        });
    }

    /**
     * Orchestration event: bridge execution.event → orchestration.event ingestion.
     */
    public void onOrchestrationEvent(UUID dispatchId, ExecutionEventPayload payload, long sequence) {
        if (payload == null || payload.eventId() == null) {
            log.warn("Discarding orchestration event — missing eventId");
            return;
        }
        try {
            eventIngestionService.ingest(
                    dispatchId, payload.eventId(), sequence, payload.eventType(), payload.data());
        } catch (IllegalStateException conflict) {
            log.warn("execution.event {} rejected: {}", payload.eventId(), conflict.getMessage());
        } catch (IllegalArgumentException unknown) {
            log.warn("execution.event {} for unknown dispatch {} — dropped", payload.eventId(), dispatchId);
        }
    }

    /**
     * Orchestration terminal: map §8.5/8.6/8.8 to OutcomeStatus and apply the
     * result under the attempt row lock. resultId = executionId; digest over
     * canonical payload bytes for deterministic replay.
     *
     * <p>Deliberately NOT @Transactional: applyResult owns its transaction
     * boundary (exactly like the legacy InboundOrchestrationHandler). A
     * joined outer tx would be poisoned when the dedup/digest conflict throws
     * and the bridge catches it — the commit would fail with
     * UnexpectedRollbackException instead of the conflict being audited and
     * dropped.</p>
     */
    public void onOrchestrationOutcome(UUID dispatchId, UUID executionId,
                                       SessionExecution.State state, Map<String, Object> payload) {
        if (state == null || payload == null) {
            return;
        }
        OrchestrationOutcomeService.OutcomeStatus status = toOutcomeStatus(state);
        if (status == null) {
            log.warn("execution.terminal state {} cannot be mapped to an orchestration outcome", state);
            return;
        }

        OrchestrationOutcomeService.RetryDisposition disposition;
        String errorCode = null;
        if (status == OrchestrationOutcomeService.OutcomeStatus.FAILED) {
            Object error = payload.get("error");
            boolean retryable = false;
            Long retryAfterSeconds = null;
            if (error instanceof Map<?, ?> errorMap) {
                Object rb = errorMap.get("retryable");
                retryable = Boolean.TRUE.equals(rb) || "true".equals(String.valueOf(rb));
                Object ra = errorMap.get("retryAfterSeconds");
                if (ra instanceof Number n) {
                    retryAfterSeconds = n.longValue();
                }
                errorCode = String.valueOf(errorMap.get("code"));
            }
            disposition = retryable
                    ? OrchestrationOutcomeService.RetryDisposition.RETRYABLE
                    : OrchestrationOutcomeService.RetryDisposition.TERMINAL;
            if (retryable && retryAfterSeconds != null) {
                payload.put("retryAfterSeconds", retryAfterSeconds);
            }
        } else {
            disposition = OrchestrationOutcomeService.RetryDisposition.NONE;
        }

        String resultDigest;
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            resultDigest = OrchestrationIds.sha256Hex(canonical);
        } catch (Exception e) {
            log.warn("Failed to compute result digest for execution {}: {}", executionId, e.getMessage());
            return;
        }

        try {
            var applied = outcomeService.applyResult(
                    dispatchId, executionId, resultDigest, status, disposition, errorCode, payload);
            log.info("execution.terminal {} for dispatch {} → {} (replay={})",
                    executionId, dispatchId, applied.status(), applied.replay());
        } catch (IllegalStateException conflict) {
            log.error("execution.terminal {} for dispatch {} REJECTED: {}",
                    executionId, dispatchId, conflict.getMessage());
        } catch (IllegalArgumentException unknown) {
            log.warn("execution.terminal {} for unknown dispatch {} — dropped", executionId, dispatchId);
        }
    }

    /**
     * Orchestration approval request: resolve the task from the dispatch and
     * persist a bounded approval payload on the WorkflowTask.
     */
    @Transactional
    public void onOrchestrationApprovalRequested(UUID dispatchId, ExecutionApprovalRequestedPayload payload) {
        if (payload == null || payload.approvalRequestId() == null) {
            log.warn("Discarding execution.approval.requested — missing identity fields");
            return;
        }
        TaskAttempt attempt = attemptRepository.findById(dispatchId).orElse(null);
        if (attempt == null) {
            log.warn("execution.approval.requested for unknown dispatch {} — dropped", dispatchId);
            return;
        }

        Map<String, Object> boundedPayload = new LinkedHashMap<>();
        boundedPayload.put("approvalRequestId", payload.approvalRequestId());
        if (payload.action() != null) {
            boundedPayload.put("actionId", payload.action().actionId());
            boundedPayload.put("actionType", payload.action().type());
            boundedPayload.put("riskClass", payload.action().riskClass());
            boundedPayload.put("summary", payload.action().summary());
            boundedPayload.put("digest", payload.action().digest());
        }
        boundedPayload.put("snapshotTreeHash", payload.snapshotTreeHash());
        boundedPayload.put("stateDigest", payload.stateDigest());
        boundedPayload.put("expiresAt", payload.expiresAt() == null ? null : payload.expiresAt().toString());

        executionApprovalService.requestApproval(
                attempt.getTask().getId(),
                "Execution approval " + payload.approvalRequestId(),
                boundedPayload,
                payload.expiresAt());
        log.info("Execution approval {} persisted for task {} (dispatch {})",
                payload.approvalRequestId(), attempt.getTask().getId(), dispatchId);
    }

    // ── helpers ────────────────────────────────────────────────────

    private Long longTokenCount(Long value) {
        return value == null ? 0L : value;
    }

    private int intTokenCount(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private void recordTokenConsumption(UUID conversationId, UUID projectId, long totalTokens) {
        try {
            UUID assistantId = conversationRepository.findById(conversationId)
                    .map(Conversation::getAssistantId).orElse(null);
            if (assistantId != null) {
                quotaPolicyEngine.recordConsumption(
                        QuotaScope.SERVICE, assistantId, QuotaResourceType.TOKENS, totalTokens);
                log.debug("Recorded {} token consumption for assistant {} (conv {})",
                        totalTokens, assistantId, conversationId);
            } else if (projectId != null) {
                quotaPolicyEngine.recordConsumption(
                        QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, totalTokens);
                log.debug("Recorded {} token consumption for project {} (conv {})",
                        totalTokens, projectId, conversationId);
            }
        } catch (Exception e) {
            log.warn("Failed to record token consumption for conv {}: {}", conversationId, e.getMessage());
        }
    }

    private Map<String, Object> buildHistoryEnvelope(ConversationMessage msg) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", msg.getConversationId().toString());
        payload.put("messageId", msg.getId().toString());
        payload.put("sequenceNo", msg.getSequenceNo());
        payload.put("role", msg.getRole() == null ? null : msg.getRole().name());
        payload.put("content", msg.getContent());
        if (msg.getAuthorUserId() != null) {
            payload.put("authorUserId", msg.getAuthorUserId().toString());
        }
        if (msg.getAuthorAgentId() != null) {
            payload.put("authorAgentId", msg.getAuthorAgentId().toString());
        }
        if (msg.getModelCode() != null) {
            payload.put("modelCode", msg.getModelCode());
        }
        if (msg.getTokenCount() != null) {
            payload.put("tokenCount", msg.getTokenCount());
        }
        if (msg.getPayloadJson() != null) {
            payload.put("payloadJson", msg.getPayloadJson());
        }
        if (msg.getApprovalStatus() != null) {
            payload.put("approvalStatus", msg.getApprovalStatus().name());
        }
        if (msg.getApproverId() != null) {
            payload.put("approverId", msg.getApproverId().toString());
        }
        if (msg.getExpiresAt() != null) {
            payload.put("expiresAt", msg.getExpiresAt().toString());
        }
        payload.put("pinned", msg.isPinned());
        if (msg.getCreatedAt() != null) {
            payload.put("createdAt", msg.getCreatedAt().toString());
        }
        return payload;
    }

    private OrchestrationOutcomeService.OutcomeStatus toOutcomeStatus(SessionExecution.State state) {
        return switch (state) {
            case COMPLETED -> OrchestrationOutcomeService.OutcomeStatus.COMPLETED;
            case FAILED -> OrchestrationOutcomeService.OutcomeStatus.FAILED;
            case PAUSED -> OrchestrationOutcomeService.OutcomeStatus.PAUSED;
            case CANCELLED -> OrchestrationOutcomeService.OutcomeStatus.CANCELLED;
            default -> null;
        };
    }
}
