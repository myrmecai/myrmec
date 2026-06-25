package ai.myrmec.engine.conversation.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ConversationStreamBroker} cooperates correctly with
 * any {@link ConversationStreamFanout} implementation:
 *
 * <ul>
 *   <li>{@link ConversationStreamBroker#broadcast} delivers locally AND
 *       calls {@link ConversationStreamFanout#publish}.</li>
 *   <li>The wired remote-handler callback delivers ONLY locally and does
 *       not re-enter publish (no amplification loop).</li>
 *   <li>A publish that throws does NOT propagate — the local turn must
 *       not fail because a peer bridge hiccupped.</li>
 * </ul>
 */
class ConversationStreamBrokerFanoutTest {

    @Test
    void broadcastDeliversLocallyAndPublishesRemote() throws Exception {
        RecordingFanout fanout = new RecordingFanout();
        ConversationStreamBroker broker = new ConversationStreamBroker(fanout);
        broker.wireRemoteHandler();

        UUID convId = UUID.randomUUID();
        List<String> outbound = new ArrayList<>();
        ConversationSubscriber subscriber = stubOpenSubscriber(outbound);
        broker.subscribe(convId, subscriber);

        int delivered = broker.broadcast(convId, "{\"type\":\"message.delta\"}");

        assertThat(delivered).isEqualTo(1);
        assertThat(outbound).containsExactly("{\"type\":\"message.delta\"}");
        assertThat(fanout.published).containsExactly(
                new RecordingFanout.Publish(convId, "{\"type\":\"message.delta\"}"));
    }

    @Test
    void remoteHandlerDeliversLocallyOnlyAndDoesNotRePublish() throws Exception {
        RecordingFanout fanout = new RecordingFanout();
        ConversationStreamBroker broker = new ConversationStreamBroker(fanout);
        broker.wireRemoteHandler();

        UUID convId = UUID.randomUUID();
        List<String> outbound = new ArrayList<>();
        broker.subscribe(convId, stubOpenSubscriber(outbound));

        // Simulate a frame arriving from a peer instance.
        fanout.remoteHandler.get().accept(convId, "{\"type\":\"message.complete\"}");

        assertThat(outbound).containsExactly("{\"type\":\"message.complete\"}");
        // CRITICAL: remote-origin frames must not be re-published — that
        // would cause an N-instance amplification storm.
        assertThat(fanout.published).isEmpty();
    }

    @Test
    void broadcastSurvivesPublishException() throws Exception {
        ConversationStreamFanout fanout = new ConversationStreamFanout() {
            @Override public void publish(UUID id, String frame) {
                throw new RuntimeException("boom");
            }
            @Override public void setRemoteHandler(BiConsumer<UUID, String> h) { }
        };
        ConversationStreamBroker broker = new ConversationStreamBroker(fanout);
        broker.wireRemoteHandler();

        UUID convId = UUID.randomUUID();
        List<String> outbound = new ArrayList<>();
        broker.subscribe(convId, stubOpenSubscriber(outbound));

        int delivered = broker.broadcast(convId, "frame");

        assertThat(delivered).isEqualTo(1);
        assertThat(outbound).containsExactly("frame");
    }

    /** In-memory subscriber that records every frame it is sent. */
    private static ConversationSubscriber stubOpenSubscriber(List<String> sink) {
        return new ConversationSubscriber() {
            private final String id = UUID.randomUUID().toString();
            @Override public String id() { return id; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) { sink.add(jsonFrame); }
        };
    }

    /** Bare-hand recording fanout — no Mockito required, all state explicit. */
    private static final class RecordingFanout implements ConversationStreamFanout {
        record Publish(UUID conversationId, String frame) { }

        final List<Publish> published = new ArrayList<>();
        final AtomicReference<BiConsumer<UUID, String>> remoteHandler = new AtomicReference<>();

        @Override public void publish(UUID conversationId, String jsonFrame) {
            published.add(new Publish(conversationId, jsonFrame));
        }

        @Override public void setRemoteHandler(BiConsumer<UUID, String> handler) {
            remoteHandler.set(handler);
        }
    }
}
