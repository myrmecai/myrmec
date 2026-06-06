package ai.myrmec.engine.conversation.stream;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Single-instance default. {@link #publish} is a deliberate no-op —
 * the broker's local fan-out is the only delivery path — and
 * {@link #setRemoteHandler} drops the handler on the floor because
 * no remote frames will ever arrive.
 */
public class NoopConversationStreamFanout implements ConversationStreamFanout {

    @Override
    public void publish(UUID conversationId, String jsonFrame) {
        // Single-instance: nothing to do. Local delivery already happened
        // inside ConversationStreamBroker.broadcast.
    }

    @Override
    public void setRemoteHandler(BiConsumer<UUID, String> handler) {
        // No remote source — handler is intentionally ignored.
    }
}
