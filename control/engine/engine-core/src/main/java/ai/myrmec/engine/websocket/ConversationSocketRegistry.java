package ai.myrmec.engine.websocket;

import ai.myrmec.engine.websocket.message.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-replica, in-memory registry of conversation-scoped WebSocket sockets
 * (agent-concurrency §9.8). A bound worker opens a dedicated socket to its
 * home node and attaches it for exactly one conversation; this map lets the
 * home node push turns + receive streamed output over that socket keyed by
 * {@code conversationId}.
 *
 * <p>This state is deliberately node-local and never shared: a socket lives
 * only on the replica the worker dialed. Cross-node turn delivery is the
 * job of {@code AgentTransport.sendToNode} (slice 4b), which routes a turn
 * to the owning replica; that replica then resolves the socket here.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSocketRegistry {

    private final ObjectMapper objectMapper;

    /** conversationId → the worker's conversation socket on this replica. */
    private final Map<UUID, WebSocketSession> byConversation = new ConcurrentHashMap<>();

    /** sessionId → conversationId, for reverse lookup on close. */
    private final Map<String, UUID> sessionToConversation = new ConcurrentHashMap<>();

    /**
     * Register a worker's conversation socket for a conversation. If a socket
     * was already attached for this conversation (stale reconnect) it is
     * displaced — the newest attach wins.
     */
    public void register(UUID conversationId, WebSocketSession session) {
        WebSocketSession previous = byConversation.put(conversationId, session);
        if (previous != null && !previous.getId().equals(session.getId())) {
            sessionToConversation.remove(previous.getId());
            log.warn("Displacing stale conversation socket for conv {} (old session {})",
                    conversationId, previous.getId());
        }
        sessionToConversation.put(session.getId(), conversationId);
        log.info("Conversation socket attached for conv {} (session {})",
                conversationId, session.getId());
    }

    /**
     * Drop a conversation socket by its session (called on close). Only
     * removes the conversation mapping if it still points at this session,
     * so a displaced stale socket closing late cannot evict the live one.
     */
    public Optional<UUID> unregisterBySession(WebSocketSession session) {
        UUID conversationId = sessionToConversation.remove(session.getId());
        if (conversationId != null) {
            byConversation.remove(conversationId, session);
            log.info("Conversation socket detached for conv {} (session {})",
                    conversationId, session.getId());
        }
        return Optional.ofNullable(conversationId);
    }

    /**
     * Resolve the conversation socket for a conversation, if attached here.
     */
    public Optional<WebSocketSession> getSession(UUID conversationId) {
        return Optional.ofNullable(byConversation.get(conversationId));
    }

    /**
     * Whether a conversation socket is currently attached on this replica.
     */
    public boolean isAttached(UUID conversationId) {
        return byConversation.containsKey(conversationId);
    }

    /**
     * Push a typed message to the conversation's socket on this replica.
     *
     * @return {@code true} if a local socket received the frame, {@code false}
     *         if no socket is attached here for that conversation
     */
    public boolean sendMessage(UUID conversationId, WebSocketMessage<?> message) {
        WebSocketSession session = byConversation.get(conversationId);
        if (session == null) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            log.error("Failed to send frame on conversation socket for conv {}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Push an already-serialized frame to the conversation's socket on this
     * replica (used by the cross-node relay path, which forwards opaque
     * frames without re-deserializing untrusted payloads).
     *
     * @return {@code true} if a local socket received the frame
     */
    public boolean sendRawMessage(UUID conversationId, String frameJson) {
        WebSocketSession session = byConversation.get(conversationId);
        if (session == null) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(frameJson));
            return true;
        } catch (IOException e) {
            log.error("Failed to relay frame on conversation socket for conv {}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }
}
