package ai.myrmec.engine.conversation.stream;

import jakarta.annotation.PostConstruct;
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
 * In-process pub/sub for conversation events.
 *
 * <p>Cross-instance fan-out (Phase 6f) is delegated to a
 * {@link ConversationStreamFanout} bean: the default
 * {@link NoopConversationStreamFanout} keeps single-instance behaviour
 * identical to Phase 6c, and clustered deployments inject the
 * Postgres LISTEN/NOTIFY variant.</p>
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

    private final ConversationStreamFanout fanout;

    public ConversationStreamBroker(ConversationStreamFanout fanout) {
        this.fanout = fanout;
    }

    /**
     * Register the local-only delivery callback with the fanout so frames
     * arriving from peer instances re-enter the broker without triggering
     * another {@link ConversationStreamFanout#publish}.
     */
    @PostConstruct
    void wireRemoteHandler() {
        fanout.setRemoteHandler(this::deliverLocal);
    }

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
     * Fan a frame out to every subscriber on the given conversation and,
     * if a clustered fan-out is configured, ask peer instances to do the
     * same. Returns the number of LOCAL recipients reached — remote
     * counts are not measured here.
     *
     * <p>Send failures to local sessions are logged and the offending
     * session is dropped so a single dead viewer can't poison the rest.</p>
     */
    public int broadcast(UUID conversationId, String jsonFrame) {
        int delivered = deliverLocal(conversationId, jsonFrame);
        try {
            fanout.publish(conversationId, jsonFrame);
        } catch (RuntimeException e) {
            // A flaky cross-instance bridge must never fail the local turn.
            log.warn("Cross-instance fanout publish failed for conversation {}: {}",
                    conversationId, e.toString());
        }
        return delivered;
    }

    /**
     * Local-only delivery path. Used both as the implementation of
     * {@link #broadcast} and as the callback handed to the
     * {@link ConversationStreamFanout} for inbound remote frames.
     */
    int deliverLocal(UUID conversationId, String jsonFrame) {
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
