package ai.myrmec.engine.conversation;

import ai.myrmec.engine.websocket.ConversationSocketRegistry;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.ApprovalDecisionPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 7c â€” pushes APPROVAL_DECISION frames over the agent WebSocket
 * after a human decides via the REST surface.
 *
 * <p>The dispatcher is intentionally best-effort: agents wake up on the
 * future their SDK is awaiting (resolved by the frame), but they don't
 * <em>have</em> to be online when the decision is made. A reconnecting
 * agent can re-fetch decided requests via the existing
 * {@code GET /api/v1/conversations/{id}/messages} endpoint when it
 * comes back. Logging records misses so an operator can see when an
 * approval was decided while the agent was offline.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDecisionDispatcher {

    private final ConversationSocketRegistry conversationSocketRegistry;

    /**
     * Build and dispatch the {@code approval.decision} frame for a
     * just-decided APPROVAL_REQUEST row. {@code clientRequestId} is
     * recovered from the request row's {@code payloadJson} envelope â€”
     * the agent SDK stamps it under the {@code "clientRequestId"} key
     * when it calls {@code ctx.request_approval(...)}. If the field
     * isn't present the frame still goes out (the SDK can fall back to
     * keying off {@code requestMessageId}), but the SDK can't resolve
     * its pending future in the no-clientId case â€” log a warning so the
     * mismatch is visible during integration testing.
     */
    public void dispatch(UUID conversationId, ConversationService.ApprovalDecisionResult result) {
        ApprovalDecisionPayload payload = ApprovalDecisionPayload.builder()
                .conversationId(conversationId)
                .requestMessageId(result.request().getId())
                .clientRequestId(extractClientRequestId(result.request()))
                .decision(result.request().getApprovalStatus() == null
                        ? null
                        : result.request().getApprovalStatus().name())
                .comment(result.response() != null ? result.response().getContent() : null)
                .responseMessageId(result.response() != null ? result.response().getId() : null)
                .approverUserId(result.request().getApproverId())
                .build();

        boolean delivered = conversationSocketRegistry.sendMessage(
                conversationId, WebSocketMessage.of(MessageType.APPROVAL_DECISION, payload));
        if (delivered) {
            log.debug("Dispatched approval.decision over conversation socket {} for request {}",
                    conversationId, result.request().getId());
        } else {
            log.info("No conversation socket attached for conv {} - approval.decision {} picked up on reconnect",
                    conversationId, result.request().getId());
        }
    }

    private UUID extractClientRequestId(ConversationMessage request) {
        String payloadJson = request.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        // Cheap targeted extraction â€” the agent SDK promises a stable
        // top-level "clientRequestId":"<uuid>" key. Parsing the whole
        // JSON would be honest but the volume here is one-per-decision
        // and the format is fixed.
        String key = "\"clientRequestId\"";
        int idx = payloadJson.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = payloadJson.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = payloadJson.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int closingQuote = payloadJson.indexOf('"', firstQuote + 1);
        if (closingQuote < 0) {
            return null;
        }
        String raw = payloadJson.substring(firstQuote + 1, closingQuote);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.debug("Approval request {} has clientRequestId that is not a UUID: {}",
                    request.getId(), raw);
            return null;
        }
    }
}
