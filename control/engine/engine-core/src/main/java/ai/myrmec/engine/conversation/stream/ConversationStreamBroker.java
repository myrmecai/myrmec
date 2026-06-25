package ai.myrmec.engine.conversation.stream;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process pub/sub for conversation events.
 *
 * <p>Subscribers are transport-agnostic {@link ConversationSubscriber}
 * sinks — the live client transport is Server-Sent Events
 * ({@link SseConversationSubscriber}), but the broker never references a
 * concrete transport so producers and the cross-instance fan-out stay
 * neutral.</p>
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

    /** conversationId → live sinks tuned to that conversation. */
    private final Map<UUID, Set<ConversationSubscriber>> subscribers = new ConcurrentHashMap<>();

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

    public void subscribe(UUID conversationId, ConversationSubscriber subscriber) {
        subscribers.computeIfAbsent(conversationId, k -> new CopyOnWriteArraySet<>())
                .add(subscriber);
        log.debug("Subscriber {} attached to conversation {}", subscriber.id(), conversationId);
    }

    public void unsubscribe(UUID conversationId, ConversationSubscriber subscriber) {
        Set<ConversationSubscriber> set = subscribers.get(conversationId);
        if (set != null) {
            set.remove(subscriber);
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
     * <p>Send failures to local sinks are logged and the offending
     * subscriber is dropped so a single dead viewer can't poison the rest.</p>
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
        Set<ConversationSubscriber> set = subscribers.get(conversationId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (ConversationSubscriber subscriber : set) {
            if (!subscriber.isOpen()) {
                set.remove(subscriber);
                continue;
            }
            try {
                // A single sink may not be safe for concurrent sends;
                // synchronise per subscriber so concurrent broadcasts don't
                // interleave frames.
                synchronized (subscriber) {
                    subscriber.send(jsonFrame);
                }
                delivered++;
            } catch (IOException e) {
                log.warn("Dropping subscriber {} from conversation {} on send failure: {}",
                        subscriber.id(), conversationId, e.getMessage());
                set.remove(subscriber);
            }
        }
        return delivered;
    }

    /** Test helper — count of live subscribers for a conversation. */
    public int subscriberCount(UUID conversationId) {
        Set<ConversationSubscriber> set = subscribers.get(conversationId);
        return set == null ? 0 : set.size();
    }
}
