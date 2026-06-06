package ai.myrmec.engine.conversation.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process pub/sub for conversation events. Phase 6c-1 ships only the
 * server-side broker + the agent-handler hookpoints; the user-facing
 * WebSocket handler that drains the broker lands in Phase 6c-2.
 *
 * <p>Designed as a single class with no SPI on purpose — multi-instance
 * fan-out (Phase 6f) will swap the in-process impl for one backed by
 * Postgres LISTEN/NOTIFY without changing call sites.</p>
 *
 * <p>Thread-safety: subscribers per conversation are kept in a
 * {@link CopyOnWriteArraySet} so {@link #broadcast} can iterate without
 * blocking subscribe / unsubscribe.</p>
 */
@Slf4j
@Component
public class ConversationStreamBroker {

    /** conversationId → live WebSocket sessions tuned to that conversation. */
    private final Map<UUID, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(UUID conversationId, WebSocketSession session) {
        subscribers.computeIfAbsent(conversationId, k -> new CopyOnWriteArraySet<>())
                .add(session);
        log.debug("Subscriber {} attached to conversation {}", session.getId(), conversationId);
    }

    public void unsubscribe(UUID conversationId, WebSocketSession session) {
        Set<WebSocketSession> set = subscribers.get(conversationId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                subscribers.remove(conversationId, set);
            }
        }
    }

    /**
     * Fan a frame out to every subscriber on the given conversation.
     * Send failures are logged + the offending session is dropped so a
     * single dead viewer can't poison the rest. Returns the number of
     * recipients the frame actually reached.
     */
    public int broadcast(UUID conversationId, String jsonFrame) {
        Set<WebSocketSession> set = subscribers.get(conversationId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : set) {
            if (!session.isOpen()) {
                set.remove(session);
                continue;
            }
            try {
                // WebSocketSession.sendMessage is not thread-safe; synchronise
                // per session so concurrent broadcasts don't interleave frames.
                synchronized (session) {
                    session.sendMessage(new TextMessage(jsonFrame));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("Dropping subscriber {} from conversation {} on send failure: {}",
                        session.getId(), conversationId, e.getMessage());
                set.remove(session);
            }
        }
        return delivered;
    }

    /** Test helper — count of live subscribers for a conversation. */
    public int subscriberCount(UUID conversationId) {
        Set<WebSocketSession> set = subscribers.get(conversationId);
        return set == null ? 0 : set.size();
    }
}
