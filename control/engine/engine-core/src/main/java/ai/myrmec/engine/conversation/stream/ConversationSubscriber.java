package ai.myrmec.engine.conversation.stream;

import java.io.IOException;

/**
 * Transport-agnostic sink for conversation stream frames.
 *
 * <p>{@link ConversationStreamBroker} fans frames out to subscribers
 * without knowing how each one delivers — the live client transport is
 * Server-Sent Events ({@link SseConversationSubscriber}), but the broker
 * deals only in this interface so the producer-side call sites and the
 * cross-instance fan-out stay transport-neutral.</p>
 */
public interface ConversationSubscriber {

    /** Stable identifier for log lines and equality within the broker's set. */
    String id();

    /** Whether this sink can still accept frames. Closed sinks are dropped. */
    boolean isOpen();

    /**
     * Deliver one JSON frame to the client. Implementations must be safe to
     * call while the broker holds its per-subscriber lock; a thrown
     * {@link IOException} tells the broker to drop this subscriber.
     */
    void send(String jsonFrame) throws IOException;
}
