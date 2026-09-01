// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket;

import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.security.scan.SecretLeakService;
import ai.myrmec.engine.workflow.TaskAttemptService;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.InferenceCancelledPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceCompletePayload;
import ai.myrmec.engine.websocket.message.payload.InferenceDeltaPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceFailedPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceToolCallPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceToolResultPayload;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Inbound handler for the unified inference result frames (§5.5).
 *
 * <p>Bridges {@code inference.*} frames arriving from the agent back to the
 * existing task-attempt / conversation infrastructure:</p>
 * <ul>
 *   <li>{@code inference.delta} → conversation stream broker (viewer fan-out)</li>
 *   <li>{@code inference.complete} → workflow: complete task attempt;
 *       conversation: persist ASSISTANT row + broadcast enriched frame</li>
 *   <li>{@code inference.failed} → workflow: fail task attempt;
 *       conversation: persist error notice</li>
 *   <li>{@code inference.tool_call} / {@code inference.tool_result} →
 *       execution-event recording</li>
 *   <li>{@code inference.cancelled} → workflow: cancel task attempt;
 *       conversation: broadcast cancellation</li>
 * </ul>
 *
 * <p>The handler resolves the service type from the session row (looked up by
 * {@code sessionId}) and dispatches to the appropriate path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundInferenceHandler {

    private final SessionRepository sessionRepository;
    private final SessionContextAssembler sessionContextAssembler;
    private final TaskAttemptService taskAttemptService;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final ConversationStreamBroker conversationStreamBroker;
    private final SecretLeakService secretLeakService;
    private final AgentRepository agentInstanceRepository;
    private final ObjectMapper objectMapper;
    private final QuotaPolicyEngine quotaPolicyEngine;

    // ───────────────────── delta ─────────────────────

    /**
     * {@code inference.delta} — streamed token chunk (conversation path only).
     */
    public void onInferenceDelta(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        InferenceDeltaPayload delta = objectMapper.convertValue(payload, InferenceDeltaPayload.class);
        Session session = lookupSession(delta.sessionId());
        if (session == null) {
            log.warn("inference.delta for unknown session {} — dropping", delta.sessionId());
            return;
        }
        if ("CONVERSATION".equals(session.getServiceType())) {
            // Fan out to SSE viewers — refId is the conversation id
            int delivered = conversationStreamBroker.broadcast(session.getRefId(), rawFrame);
            log.debug("inference.delta conv {} seq {}#{} ({} chars) → {} viewer(s)",
                    session.getRefId(), delta.sequenceNo(), delta.deltaIndex(),
                    delta.content() == null ? 0 : delta.content().length(), delivered);
        } else {
            log.debug("inference.delta for WORKFLOW session {} — ignored (non-streaming)", delta.sessionId());
        }
    }

    // ─────────────────── complete ────────────────────

    /**
     * {@code inference.complete} — final assistant content + token usage.
     */
    @Transactional
    public void onInferenceComplete(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        InferenceCompletePayload complete = objectMapper.convertValue(payload, InferenceCompletePayload.class);
        Session session = lookupSession(complete.sessionId());
        if (session == null) {
            log.warn("inference.complete for unknown session {} — dropping", complete.sessionId());
            return;
        }

        if ("WORKFLOW".equals(session.getServiceType())) {
            handleWorkflowComplete(agentInstanceId, complete, session);
            // Workflows are one-shot: close the session after the task completes.
            sessionContextAssembler.closeSession(complete.sessionId());
        } else {
            handleConversationComplete(agentInstanceId, complete, session, rawFrame);
            // Conversations are multi-turn: keep the session open so the
            // sticky-dispatch path (dispatchOverBoundSocket) can reuse it
            // for the next turn. The session is closed when the conversation
            // is archived or the agent is released.
        }
    }

    private void handleWorkflowComplete(UUID agentInstanceId, InferenceCompletePayload complete,
                                         Session session) {
        // requestId = task id for the workflow path
        UUID taskId = complete.requestId();
        UUID attemptId = taskAttemptService.findCurrentAttemptId(taskId).orElse(null);
        log.info("Agent {} completed inference for task {} (attempt {})",
                agentInstanceId, taskId, attemptId);
        if (attemptId != null) {
            try {
                // Wrap the inference content in the output map format expected
                // by TaskAttemptService.completeSuccess (legacy task.output)
                java.util.Map<String, Object> output = new java.util.HashMap<>();
                output.put("content", complete.content());
                if (complete.tokenCount() != null) output.put("tokenCount", complete.tokenCount());
                if (complete.modelCode() != null) output.put("modelCode", complete.modelCode());
                taskAttemptService.completeSuccess(attemptId, output);
            } catch (Exception e) {
                log.error("Failed to complete task attempt {}: {}", attemptId, e.getMessage(), e);
                // R4: don't strand the attempt in RUNNING — mark it failed
                try {
                    taskAttemptService.completeFailed(attemptId,
                            "Inference completed but result could not be saved: " + e.getMessage(),
                            "RESULT_PERSIST_ERROR", null);
                } catch (Exception e2) {
                    log.error("Failed to mark attempt {} as failed after completion error: {}",
                            attemptId, e2.getMessage(), e2);
                }
            }
        } else {
            log.warn("No attempt found for completed task {}", taskId);
        }
    }

    private void handleConversationComplete(UUID agentInstanceId, InferenceCompletePayload complete,
                                             Session session, String rawFrame) {
        UUID conversationId = session.getRefId();
        // Secret-leak scan before persisting / fanning out
        SecretLeakService.Result scan = secretLeakService.inspectOutbound(
                complete.content(), conversationId, null);
        if (scan.isBlocked()) {
            log.warn("BLOCKED inference.complete from agent {} (conv {}) — secret leak detected",
                    agentInstanceId, conversationId);
            return;
        }
        String safeText = scan.getText();
        String safeRawFrame = rawFrame;
        if (scan.isLeakDetected() && safeText != null
                && !safeText.equals(complete.content())) {
            try {
                InferenceCompletePayload clean = new InferenceCompletePayload(
                        complete.requestId(), complete.sessionId(), complete.sequenceNo(),
                        safeText, complete.tokenCount(), complete.modelCode());
                WebSocketMessage<InferenceCompletePayload> envelope =
                        WebSocketMessage.of(MessageType.INFERENCE_COMPLETE, clean);
                safeRawFrame = objectMapper.writeValueAsString(envelope);
            } catch (Exception ex) {
                log.warn("Failed to re-serialise redacted inference.complete (conv {}): {}",
                        conversationId, ex.getMessage());
                return;
            }
        }
        // Persist the ASSISTANT row
        UUID agentId = agentInstanceRepository.findById(agentInstanceId)
                .map(Agent::getAgentHostId).orElse(null);
        ConversationMessage saved = null;
        try {
            saved = conversationService.appendMessage(
                    conversationId,
                    ConversationMessage.Role.ASSISTANT,
                    safeText,
                    null,    // authorUserId — null for agent-authored messages
                    agentId);
        } catch (Exception e) {
            log.error("Failed to persist assistant message for conv {}: {}",
                    conversationId, e.getMessage(), e);
        }
        // Record token consumption so the pre-flight check in
        // ConversationTurnDispatcher sees the updated consumption on the
        // next turn.
        //
        // When the conversation has a pinned assistant, record at SERVICE
        // scope (scopeId = assistantId). buildScopeChain walks
        // SERVICE → PROJECT → GROUP → ORG and records at each level, so a
        // single call cascades to all ancestor scopes.
        //
        // For legacy conversations with no assistant, fall back to PROJECT
        // scope (scopeId = projectId).
        if (complete.tokenCount() != null && complete.tokenCount() > 0) {
            try {
                UUID assistantId = conversationRepository
                        .findById(conversationId)
                        .map(Conversation::getAssistantId)
                        .orElse(null);
                if (assistantId != null) {
                    quotaPolicyEngine.recordConsumption(
                            QuotaScope.SERVICE, assistantId,
                            QuotaResourceType.TOKENS, complete.tokenCount());
                    log.debug("Recorded {} token consumption for assistant {} (conv {})",
                            complete.tokenCount(), assistantId, conversationId);
                } else if (session.getProjectId() != null) {
                    quotaPolicyEngine.recordConsumption(
                            QuotaScope.PROJECT, session.getProjectId(),
                            QuotaResourceType.TOKENS, complete.tokenCount());
                    log.debug("Recorded {} token consumption for project {} (conv {})",
                            complete.tokenCount(), session.getProjectId(), conversationId);
                }
            } catch (Exception e) {
                log.warn("Failed to record token consumption for conv {}: {}",
                        conversationId, e.getMessage());
            }
        }
        // Fan out a message.complete frame to SSE viewers so the UI refetches
        // (the UI expects message.* events, not inference.* — we bridge here
        // by building an enriched message.complete envelope from the persisted
        // row, exactly like ConversationInboundService.onMessageComplete did).
        if (saved != null) {
            try {
                // Build an enriched message.complete payload that carries the
                // full persisted row shape (messageId, role, createdAt, etc.)
                // so the UI's mergeHistory can insert it directly.
                java.util.Map<String, Object> enriched = new java.util.LinkedHashMap<>();
                enriched.put("conversationId", conversationId.toString());
                enriched.put("messageId", saved.getId().toString());
                enriched.put("sequenceNo", saved.getSequenceNo());
                enriched.put("role", "ASSISTANT");
                enriched.put("content", saved.getContent());
                enriched.put("authorAgentId", agentId != null ? agentId.toString() : null);
                enriched.put("modelCode", complete.modelCode());
                enriched.put("tokenCount", complete.tokenCount());
                enriched.put("createdAt", saved.getCreatedAt() != null
                        ? saved.getCreatedAt().toString() : java.time.Instant.now().toString());
                WebSocketMessage<java.util.Map<String, Object>> envelope =
                        WebSocketMessage.of(MessageType.MESSAGE_COMPLETE, enriched);
                String mcFrame = objectMapper.writeValueAsString(envelope);
                conversationStreamBroker.broadcast(conversationId, mcFrame);
                log.info("Bridged message.complete to SSE viewers for conv {} (seq {})",
                        conversationId, saved.getSequenceNo());
            } catch (Exception ex) {
                log.warn("Failed to build message.complete bridge for conv {}: {}",
                        conversationId, ex.getMessage());
            }
        }
    }

    // ──────────────────── failed ─────────────────────

    /**
     * {@code inference.failed} — turn failed with error.
     */
    @Transactional
    public void onInferenceFailed(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        InferenceFailedPayload failed = objectMapper.convertValue(payload, InferenceFailedPayload.class);
        Session session = lookupSession(failed.sessionId());
        if (session == null) {
            log.warn("inference.failed for unknown session {} — dropping", failed.sessionId());
            return;
        }

        log.warn("Agent {} inference failed for session {} [{}]: {}",
                agentInstanceId, failed.sessionId(), failed.errorCode(), failed.message());

        if ("WORKFLOW".equals(session.getServiceType())) {
            UUID taskId = failed.requestId();
            UUID attemptId = taskAttemptService.findCurrentAttemptId(taskId).orElse(null);
            if (attemptId != null) {
                try {
                    Integer retryAfter = failed.retryHint() != null
                            ? parseRetryHint(failed.retryHint()) : null;
                    taskAttemptService.completeFailed(
                            attemptId, failed.message(), failed.errorCode(), retryAfter);
                } catch (Exception e) {
                    log.error("Failed to record task failure: {}", e.getMessage(), e);
                }
            }
        } else {
            // Conversation — broadcast an error notice to viewers
            conversationStreamBroker.broadcast(session.getRefId(), rawFrame);
        }

        // Close session for workflows only; conversations keep the session open
        // for multi-turn sticky dispatch.
        if ("WORKFLOW".equals(session.getServiceType())) {
            sessionContextAssembler.closeSession(failed.sessionId());
        }
    }

    // ──────────────── tool_call / tool_result ─────────

    /**
     * {@code inference.tool_call} — agent's model requested a tool.
     */
    public void onInferenceToolCall(UUID agentInstanceId, JsonNode payload) {
        InferenceToolCallPayload tc = objectMapper.convertValue(payload, InferenceToolCallPayload.class);
        log.debug("Agent {} session {} tool call: {} ({})",
                agentInstanceId, tc.sessionId(), tc.name(), tc.toolCallId());
        // Tool-call recording for inference frames is lightweight for now;
        // the detailed execution-event wiring (TaskAttempt linkage) is
        // handled by the existing TOOL_CALL / TOOL_RESULT frames that the
        // agent still emits alongside the inference frames.
    }

    /**
     * {@code inference.tool_result} — result of a tool execution.
     */
    public void onInferenceToolResult(UUID agentInstanceId, JsonNode payload) {
        InferenceToolResultPayload tr = objectMapper.convertValue(payload, InferenceToolResultPayload.class);
        log.debug("Agent {} session {} tool result for {}: {}",
                agentInstanceId, tr.sessionId(), tr.toolCallId(),
                tr.isError() ? "error" : "success");
    }

    // ─────────────────── cancelled ────────────────────

    /**
     * {@code inference.cancelled} — ack of cancellation.
     */
    @Transactional
    public void onInferenceCancelled(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        InferenceCancelledPayload cancelled = objectMapper.convertValue(payload, InferenceCancelledPayload.class);
        Session session = lookupSession(cancelled.sessionId());
        if (session == null) {
            log.warn("inference.cancelled for unknown session {} — dropping", cancelled.sessionId());
            return;
        }
        log.info("Agent {} cancelled inference for session {} seq {}",
                agentInstanceId, cancelled.sessionId(), cancelled.sequenceNo());

        if ("WORKFLOW".equals(session.getServiceType())) {
            UUID taskId = cancelled.requestId();
            // Mark the task as cancelled — update the task row directly.
            // Idempotency guard: if the task is already CANCELLED (e.g. the
            // engine's cancel cascade beat the agent's acknowledgement),
            // skip the status update and just close the session.
            WorkflowTask task = workflowTaskRepository.findById(taskId).orElse(null);
            if (task != null && task.getStatus() != TaskStatus.CANCELLED) {
                task.setStatus(TaskStatus.CANCELLED);
                workflowTaskRepository.save(task);
            }
        } else {
            conversationStreamBroker.broadcast(session.getRefId(), rawFrame);
        }

        // Close session for workflows only; conversations keep the session open
        // for multi-turn sticky dispatch.
        if ("WORKFLOW".equals(session.getServiceType())) {
            sessionContextAssembler.closeSession(cancelled.sessionId());
        }
    }

    // ─────────────────── helpers ──────────────────────

    private Session lookupSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    private Integer parseRetryHint(String retryHint) {
        try {
            return Integer.parseInt(retryHint.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }
}