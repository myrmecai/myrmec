package ai.myrmec.engine.conversation.stream;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Cross-instance fan-out abstraction for {@link ConversationStreamBroker}.
 *
 * <p>Phase 6c shipped only an in-process broker. Phase 6f introduces this
 * SPI so a clustered deployment can swap the implementation for one
 * backed by Postgres {@code LISTEN/NOTIFY} (the bundled
 * {@code PgListenNotifyConversationStreamFanout}) — and any future
 * Redis / Kafka / NATS bridge — without touching call sites in the
 * broker, the WebSocket handlers, or the dispatcher.</p>
 *
 * <p>Implementations MUST be safe to call from many threads and MUST
 * filter out frames that originated from this instance, so a publish
 * does not cause a self-deliver loop through {@link #setRemoteHandler}.</p>
 */
public interface ConversationStreamFanout {

    /**
     * Publish a frame so peer instances can deliver it to their local
     * subscribers. The local instance must have already delivered to its
     * own subscribers before calling — this method is only the
     * "tell everyone else" half.
     */
    void publish(UUID conversationId, String jsonFrame);

    /**
     * Wire the callback used when a frame arrives from a peer instance.
     * The broker registers a handler that performs ONLY local delivery
     * (no further {@link #publish}) to avoid amplification storms.
     *
     * <p>Implementations should call the handler at most once per
     * remote frame and MUST NOT call it for frames they themselves
     * published.</p>
     */
    void setRemoteHandler(BiConsumer<UUID, String> handler);
}
