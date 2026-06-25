package ai.myrmec.engine.websocket;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentService;
import ai.myrmec.engine.conversation.ContextSummaryMarker;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.ConversationSummaryService;
import ai.myrmec.engine.conversation.dispatch.SummaryInFlightRegistry;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.security.scan.SecretLeakService;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.ApprovalRequestPayload;
import ai.myrmec.engine.websocket.message.payload.MessageCompletePayload;
import ai.myrmec.engine.websocket.message.payload.MessageDeltaPayload;
import ai.myrmec.engine.websocket.message.payload.TaskCancelledPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared handling for the conversation-streaming frames a bound worker sends
 * back during a turn ({@code message.delta}, {@code message.complete},
 * {@code task.cancelled}). Extracted from {@link AgentWebSocketHandler} in
 * slice 4c-2 so the same logic serves the conversation socket
 * ({@link AgentConversationWebSocketHandler}) — the socket these frames now
 * arrive on after the turn-stream cutover.
 *
 * <p>Every entry point takes the already-resolved {@code agentInstanceId}
 * (read from the socket's JWT-pinned session attribute by the caller) plus the
 * parsed payload node and the original raw frame (forwarded verbatim to
 * viewers).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationInboundService {

    private final AgentRepository agentInstanceRepository;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ConversationStreamBroker conversationStreamBroker;
    private final SecretLeakService secretLeakService;
    private final ObjectMapper objectMapper;
    private final SummaryInFlightRegistry summaryInFlightRegistry;
    private final ConversationSummaryService conversationSummaryService;

    /**
     * {@code message.delta} — streamed assistant token chunk. Fans the raw
     * frame out to subscribed viewers via the broker; persists nothing (the
     * canonical text lands on {@code message.complete}).
     */
    public void onMessageDelta(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        MessageDeltaPayload delta = objectMapper.convertValue(payload, MessageDeltaPayload.class);
        int delivered = conversationStreamBroker.broadcast(delta.getConversationId(), rawFrame);
        log.debug("Agent {} streamed delta for conversation {} seq {}#{} ({} chars) -> {} viewer(s)",
                agentInstanceId,
                delta.getConversationId(),
                delta.getSequenceNo(),
                delta.getDeltaIndex(),
                delta.getContent() == null ? 0 : delta.getContent().length(),
                delivered);
    }

    /**
     * {@code message.complete} — final assistant turn. Runs the outbound
     * secret-leak scan, fans the (possibly redacted) frame to viewers,
     * persists the canonical ASSISTANT row for replay, then releases the
     * worker back to the warm pool.
     */
    public void onMessageComplete(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        MessageCompletePayload complete = objectMapper.convertValue(payload, MessageCompletePayload.class);

        // #8 — if a summarisation turn is in flight for this conversation, this
        // completion is the summary itself: route it into a CONTEXT_SUMMARY row
        // (not an ASSISTANT row), suppress the viewer fan-out, and do NOT
        // re-trigger summarisation (loop guard). Consuming the marker also
        // clears the in-flight latch so future CHAT turns can trigger again.
        Optional<ContextSummaryMarker> summaryMark =
                summaryInFlightRegistry.consume(complete.getConversationId());
        if (summaryMark.isPresent()) {
            handleSummaryComplete(agentInstanceId, complete, summaryMark.get());
            return;
        }

        // Phase 9e — secret-leak scan BEFORE we fan out to viewers or
        // persist for replay. The scanner can BLOCK (drop the frame
        // entirely) or REDACT (keep going with cleaned text). The
        // original text is never persisted in either case.
        SecretLeakService.Result scan =
                secretLeakService.inspectOutbound(
                        complete.getContent(),
                        complete.getConversationId(),
                        null);
        if (scan.isBlocked()) {
            log.warn("BLOCKED message.complete from agent {} (conv {}) — secret leak detected",
                    agentInstanceId, complete.getConversationId());
            return;
        }
        String safeText = scan.getText();
        String safeRawFrame = rawFrame;
        if (scan.isLeakDetected() && safeText != null
                && !safeText.equals(complete.getContent())) {
            // Re-serialise the envelope so viewers see the redacted text.
            try {
                MessageCompletePayload cleanPayload = new MessageCompletePayload();
                cleanPayload.setConversationId(complete.getConversationId());
                cleanPayload.setSequenceNo(complete.getSequenceNo());
                cleanPayload.setContent(safeText);
                cleanPayload.setModelCode(complete.getModelCode());
                cleanPayload.setTokenCount(complete.getTokenCount());
                WebSocketMessage<MessageCompletePayload> envelope =
                        WebSocketMessage.of(MessageType.MESSAGE_COMPLETE, cleanPayload);
                safeRawFrame = objectMapper.writeValueAsString(envelope);
            } catch (Exception ex) {
                // Re-serialisation failures fall through to the original
                // raw frame — but that exposes the leak. Better to drop.
                log.warn("Failed to re-serialise redacted message.complete (conv {}): {}",
                        complete.getConversationId(), ex.getMessage());
                return;
            }
        }

        // Fan to live viewers first — persistence below is for replay only.
        conversationStreamBroker.broadcast(complete.getConversationId(), safeRawFrame);
        try {
            UUID agentId = agentInstanceRepository.findById(agentInstanceId)
                    .map(Agent::getAgentHostId)
                    .orElse(null);
            ConversationMessage saved = conversationService.appendMessage(
                    complete.getConversationId(),
                    ConversationMessage.Role.ASSISTANT,
                    safeText,
                    null,
                    agentId);
            if (complete.getModelCode() != null) {
                saved.setModelCode(complete.getModelCode());
            }
            if (complete.getTokenCount() != null) {
                saved.setTokenCount(complete.getTokenCount());
            }
            log.debug("Persisted ASSISTANT message {} (conv {} seq {})",
                    saved.getId(), complete.getConversationId(), saved.getSequenceNo());
        } catch (Exception e) {
            log.warn("Failed to persist message.complete from agent {}: {}",
                    agentInstanceId, e.getMessage(), e);
        }

        // §9.5 — the worker stays BOUND between turns (sticky binding). It is
        // released to IDLE only when the conversation goes IDLE/CLOSED (user
        // away, archive, or idle timeout), not after every turn. The next
        // turn reuses the live conversation socket without a reserve/bind
        // cycle (ConversationTurnDispatcher.dispatch checks for an existing
        // BOUND worker first).

        // #8 — off the turn critical path, check whether the conversation has
        // grown past the summarisation threshold and, if so, dispatch a
        // summary turn on a (possibly different) idle worker. Best-effort:
        // never let a summarisation failure affect the completed turn.
        try {
            conversationSummaryService.summariseIfNeeded(complete.getConversationId());
        } catch (Exception e) {
            log.warn("Post-turn summarisation check failed for conv {}: {}",
                    complete.getConversationId(), e.getMessage());
        }
    }

    /**
     * Persist a completed summarisation turn (#8) as a
     * {@link ConversationMessage.Role#CONTEXT_SUMMARY} row carrying the summary
     * text plus the coverage marker, then release the worker. The summary frame
     * is deliberately NOT fanned out to viewers as an assistant message; the UI
     * renders the persisted CONTEXT_SUMMARY row as a fold marker instead.
     */
    private void handleSummaryComplete(UUID agentInstanceId,
                                       MessageCompletePayload complete,
                                       ContextSummaryMarker marker) {
        try {
            SecretLeakService.Result scan =
                    secretLeakService.inspectOutbound(
                            complete.getContent(),
                            complete.getConversationId(),
                            null);
            if (scan.isBlocked()) {
                log.warn("BLOCKED summary completion from agent {} (conv {}) — secret leak detected",
                        agentInstanceId, complete.getConversationId());
            } else {
                String safeText = scan.getText();
                UUID agentId = agentInstanceRepository.findById(agentInstanceId)
                        .map(Agent::getAgentHostId)
                        .orElse(null);
                // #8a — enrich the coverage marker into the durable audit
                // record: stamp the model + token count the summary turn
                // reported so an AUDITOR can reconstruct exactly what the model
                // produced (trigger + replaced range were set at dispatch time).
                ContextSummaryMarker auditMarker =
                        marker.withCompletion(complete.getModelCode(), complete.getTokenCount());
                String payloadJson = objectMapper.writeValueAsString(auditMarker);
                ConversationMessage saved = conversationService.appendContextSummary(
                        complete.getConversationId(), safeText, payloadJson, agentId);
                log.info("Persisted CONTEXT_SUMMARY {} (conv {} seq {}) trigger={} model={} tokens={} "
                                + "folding {} msg(s) seq {}..{}",
                        saved.getId(), complete.getConversationId(), saved.getSequenceNo(),
                        auditMarker.trigger(), auditMarker.modelCode(), auditMarker.tokenCount(),
                        auditMarker.summarizedMessageCount(), auditMarker.coversFromSequenceNo(),
                        auditMarker.coversUpToSequenceNo());
            }
        } catch (Exception e) {
            log.warn("Failed to persist summary completion (conv {}): {}",
                    complete.getConversationId(), e.getMessage(), e);
        }
        // §9.5 — keep the worker BOUND; it is released when the conversation
        // goes IDLE/CLOSED, not after each turn (including summary turns).
    }

    /**
     * {@code task.cancelled} — agent acknowledged a turn cancel. Broadcasts
     * the cancellation frame to viewers, persists whatever partial assistant
     * text was streamed, then releases the worker.
     */
    public void onTaskCancelled(UUID agentInstanceId, JsonNode payload, String rawFrame) {
        TaskCancelledPayload cancelled = objectMapper.convertValue(payload, TaskCancelledPayload.class);
        if (cancelled.getConversationId() != null) {
            conversationStreamBroker.broadcast(cancelled.getConversationId(), rawFrame);
        }
        log.info("Agent {} acknowledged cancellation of task {} (conv {}, reason: {})",
                agentInstanceId,
                cancelled.getTaskId(),
                cancelled.getConversationId(),
                cancelled.getReason());

        // Phase 6d — persist whatever the assistant had streamed before being cancelled.
        // This preserves the partial reply for replay viewers and gives the user something
        // to see in the transcript even though the turn never completed.
        if (cancelled.getConversationId() != null
                && cancelled.getPartialContent() != null
                && !cancelled.getPartialContent().isEmpty()) {
            try {
                UUID agentId = agentInstanceRepository.findById(agentInstanceId)
                        .map(Agent::getAgentHostId)
                        .orElse(null);
                ConversationMessage saved = conversationService.appendMessage(
                        cancelled.getConversationId(),
                        ConversationMessage.Role.ASSISTANT,
                        cancelled.getPartialContent(),
                        null,
                        agentId);
                log.debug("Persisted partial ASSISTANT message {} (conv {} seq {}) after cancel",
                        saved.getId(), cancelled.getConversationId(), saved.getSequenceNo());
            } catch (Exception e) {
                log.warn("Failed to persist partial assistant turn after cancel for conv {}: {}",
                        cancelled.getConversationId(), e.getMessage(), e);
            }
        }

        // §9.5 — keep the worker BOUND after a cancel; the next turn reuses
        // the live conversation socket. The worker is released when the
        // conversation goes IDLE/CLOSED.
    }

    /**
     * {@code approval.request} — the agent is gating a side-effecting action
     * behind a human decision. Persist the APPROVAL_REQUEST row via
     * {@link ConversationService#appendApprovalRequest} (so it shows in
     * replay) then broadcast an enriched envelope to viewers carrying both the
     * engine-assigned {@code messageId} and the agent's {@code clientRequestId}.
     * The decision arrives later through the REST surface and is routed back
     * over this same conversation socket by {@code ApprovalDecisionDispatcher}.
     *
     * <p>Moved here from {@link AgentWebSocketHandler} in slice 4c-2b so the
     * full HITL loop rides the conversation socket.</p>
     */
    public void onApprovalRequest(UUID agentInstanceId, JsonNode payload) {
        ApprovalRequestPayload req = objectMapper.convertValue(payload, ApprovalRequestPayload.class);
        if (req.getConversationId() == null || req.getClientRequestId() == null) {
            log.warn("Discarding approval.request from agent {} — conversationId/clientRequestId required",
                    agentInstanceId);
            return;
        }
        UUID agentId = agentInstanceRepository.findById(agentInstanceId)
                .map(Agent::getAgentHostId)
                .orElse(null);
        if (agentId == null) {
            log.warn("Agent instance {} sent approval.request but no Agent row — dropping", agentInstanceId);
            return;
        }
        ConversationMessage persisted;
        try {
            persisted = conversationService.appendApprovalRequest(
                    req.getConversationId(),
                    agentId,
                    req.getContent(),
                    req.getPayloadJson(),
                    req.getExpiresAt());
        } catch (Exception e) {
            log.warn("Failed to persist approval.request for conv {} from agent {}: {}",
                    req.getConversationId(), agentInstanceId, e.getMessage(), e);
            return;
        }
        // Broadcast to viewers with both ids populated so the UI knows which row
        // to render and the originating agent's other viewers (multi-tab admin)
        // see the same envelope.
        ApprovalRequestPayload broadcast = ApprovalRequestPayload.builder()
                .conversationId(req.getConversationId())
                .clientRequestId(req.getClientRequestId())
                .messageId(persisted.getId())
                .content(persisted.getContent())
                .payloadJson(persisted.getPayloadJson())
                .expiresAt(persisted.getExpiresAt())
                .build();
        WebSocketMessage<ApprovalRequestPayload> envelope =
                WebSocketMessage.of(MessageType.APPROVAL_REQUEST, broadcast);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            conversationStreamBroker.broadcast(req.getConversationId(), json);
        } catch (Exception e) {
            log.warn("Failed to serialise approval.request envelope for conv {}: {}",
                    req.getConversationId(), e.getMessage());
        }
        log.info("Agent {} requested approval on conv {} (clientId {}, persisted as {})",
                agentInstanceId, req.getConversationId(), req.getClientRequestId(), persisted.getId());
    }
}
