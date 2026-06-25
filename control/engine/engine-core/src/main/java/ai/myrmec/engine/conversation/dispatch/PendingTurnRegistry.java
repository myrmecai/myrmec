package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-replica buffer that holds an assembled {@code conversation.turn.assign}
 * payload until the worker opens + attaches its conversation socket
 * (agent-concurrency §9.4).
 *
 * <p>The dispatcher reserves a worker and sends {@code agent.bind} over the
 * control socket, but the turn itself rides the conversation socket — which
 * does not exist yet at dispatch time. The worker reacts to the bind by
 * dialing its home node and sending {@code conversation.attach}; only then can
 * the turn be delivered. The dispatcher therefore <em>buffers</em> the turn
 * here keyed by {@code conversationId}, and the conversation-socket handler
 * flushes it on attach.</p>
 *
 * <p>State is node-local and never shared: the bind carries this replica's own
 * address as the home node, so the worker always attaches back to the replica
 * that dispatched (and therefore buffered) the turn.</p>
 */
@Slf4j
@Component
public class PendingTurnRegistry {

    /** conversationId → the turn awaiting its conversation socket. */
    private final Map<UUID, ConversationTurnAssignPayload> pending = new ConcurrentHashMap<>();

    /**
     * Buffer a turn for delivery once the worker's conversation socket
     * attaches. A newer turn for the same conversation displaces an older
     * un-flushed one (the latest dispatch wins).
     */
    public void enqueue(UUID conversationId, ConversationTurnAssignPayload payload) {
        ConversationTurnAssignPayload previous = pending.put(conversationId, payload);
        if (previous != null) {
            log.warn("Replacing an un-flushed pending turn for conversation {}", conversationId);
        }
    }

    /**
     * Remove and return the buffered turn for a conversation, if any. Called by
     * the conversation-socket handler on {@code conversation.attach}.
     */
    public Optional<ConversationTurnAssignPayload> take(UUID conversationId) {
        return Optional.ofNullable(pending.remove(conversationId));
    }

    /** Drop a buffered turn without delivering it (e.g. on reservation teardown). */
    public void discard(UUID conversationId) {
        pending.remove(conversationId);
    }
}
