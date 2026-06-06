package ai.myrmec.engine.websocket;

import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing WebSocket handler — one session per browser tab listening
 * to a single conversation. Subscribes on connect, replays the message
 * history once for late joiners, and drains live frames produced by
 * {@link ConversationStreamBroker} (fed by the agent handler).
 *
 * <p>Inbound frames are currently ignored — Phase 6c-2 only supports
 * server-to-client streaming. Client-driven actions (typing indicators,
 * cancel buttons) will reuse this socket in Phase 6d.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserConversationWebSocketHandler extends TextWebSocketHandler {

    private final ConversationStreamBroker broker;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID conversationId = (UUID) session.getAttributes()
                .get(UserConversationHandshakeInterceptor.ATTR_CONVERSATION_ID);
        UUID userId = (UUID) session.getAttributes()
                .get(UserConversationHandshakeInterceptor.ATTR_USER_ID);
        log.debug("User WS connected: user {} -> conversation {}", userId, conversationId);

        // Replay durable history once so a late joiner sees prior turns
        // before live streaming kicks in. Each row goes out as a
        // "history.message" envelope to keep it distinguishable from
        // live message.delta / message.complete frames.
        List<ConversationMessage> messages = conversationService.listMessages(conversationId);
        for (ConversationMessage msg : messages) {
            String frame = objectMapper.writeValueAsString(buildHistoryEnvelope(msg));
            session.sendMessage(new TextMessage(frame));
        }

        broker.subscribe(conversationId, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID conversationId = (UUID) session.getAttributes()
                .get(UserConversationHandshakeInterceptor.ATTR_CONVERSATION_ID);
        if (conversationId != null) {
            broker.unsubscribe(conversationId, session);
        }
        log.debug("User WS closed: session {} (status {})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Phase 6c-2 is server-push only; ignore inbound for now.
        log.debug("Ignoring inbound user-WS frame on session {}", session.getId());
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
        if (msg.getCreatedAt() != null) {
            payload.put("createdAt", msg.getCreatedAt().toString());
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "history.message");
        envelope.put("payload", payload);
        return envelope;
    }
}
