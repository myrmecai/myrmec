package ai.myrmec.engine.conversation.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit-level coverage for the Postgres LISTEN/NOTIFY fanout that does
 * NOT require a running Postgres. Exercises the bits we own:
 *
 * <ul>
 *   <li>Envelope JSON shape (origin instance id + conversation id +
 *       inner frame string).</li>
 *   <li>{@link PgListenNotifyConversationStreamFanout#handleNotification}
 *       filters out self-originated frames so a broadcast doesn't echo.</li>
 *   <li>Malformed envelopes are dropped without throwing.</li>
 *   <li>Remote-handler delivery passes through the inner frame verbatim
 *       (no double-encoding).</li>
 * </ul>
 *
 * <p>The live LISTEN loop + {@code pg_notify} round-trip are an
 * integration concern that needs Testcontainers; defer to a separate
 * harness once one is added (the H2 default test profile cannot
 * exercise it).</p>
 */
class PgListenNotifyConversationStreamFanoutTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selfOriginatedFramesAreSkipped() throws Exception {
        PgListenNotifyConversationStreamFanout fanout =
                new PgListenNotifyConversationStreamFanout(mock(DataSource.class), objectMapper);
        List<String> delivered = new ArrayList<>();
        fanout.setRemoteHandler((id, frame) -> delivered.add(frame));

        String selfEnvelope = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("originInstanceId", fanout.instanceId())
                        .put("conversationId", UUID.randomUUID().toString())
                        .put("frame", "{\"type\":\"message.delta\"}"));

        fanout.handleNotification(selfEnvelope);

        assertThat(delivered).isEmpty();
    }

    @Test
    void peerFramesAreDeliveredVerbatim() throws Exception {
        PgListenNotifyConversationStreamFanout fanout =
                new PgListenNotifyConversationStreamFanout(mock(DataSource.class), objectMapper);
        AtomicReference<UUID> seenConv = new AtomicReference<>();
        AtomicReference<String> seenFrame = new AtomicReference<>();
        fanout.setRemoteHandler((id, frame) -> {
            seenConv.set(id);
            seenFrame.set(frame);
        });

        UUID convId = UUID.randomUUID();
        String innerFrame = "{\"type\":\"message.complete\",\"payload\":{\"content\":\"hi\"}}";
        String envelope = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("originInstanceId", UUID.randomUUID().toString())
                        .put("conversationId", convId.toString())
                        .put("frame", innerFrame));

        fanout.handleNotification(envelope);

        assertThat(seenConv.get()).isEqualTo(convId);
        // The frame field MUST pass through unmangled — viewers expect the
        // exact bytes the agent sent on the WS, not a re-serialised copy.
        assertThat(seenFrame.get()).isEqualTo(innerFrame);
    }

    @Test
    void malformedEnvelopesAreSwallowed() {
        PgListenNotifyConversationStreamFanout fanout =
                new PgListenNotifyConversationStreamFanout(mock(DataSource.class), objectMapper);
        List<String> delivered = new ArrayList<>();
        fanout.setRemoteHandler((id, frame) -> delivered.add(frame));

        // All three of these would individually be cause for an exception
        // if the handler weren't defensive. We assert no throw + nothing
        // delivered.
        fanout.handleNotification("");
        fanout.handleNotification("not json at all");
        fanout.handleNotification("{\"originInstanceId\":\"" + UUID.randomUUID()
                + "\",\"conversationId\":\"not-a-uuid\",\"frame\":\"x\"}");
        fanout.handleNotification("{\"originInstanceId\":\"" + UUID.randomUUID()
                + "\",\"frame\":\"missing conv id\"}");

        assertThat(delivered).isEmpty();
    }

    @Test
    void envelopeShapeRoundTrips() throws Exception {
        // Lock the wire format. Any change here is a cross-version protocol
        // bump and needs explicit migration thought.
        PgListenNotifyConversationStreamFanout fanout =
                new PgListenNotifyConversationStreamFanout(mock(DataSource.class), objectMapper);
        UUID conv = UUID.randomUUID();
        String envelope = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("originInstanceId", UUID.randomUUID().toString())
                        .put("conversationId", conv.toString())
                        .put("frame", "inner"));

        JsonNode parsed = objectMapper.readTree(envelope);
        assertThat(parsed.has("originInstanceId")).isTrue();
        assertThat(parsed.has("conversationId")).isTrue();
        assertThat(parsed.has("frame")).isTrue();
        assertThat(parsed.get("conversationId").asText()).isEqualTo(conv.toString());

        // And the handler accepts it.
        List<UUID> seen = new ArrayList<>();
        fanout.setRemoteHandler((id, frame) -> seen.add(id));
        fanout.handleNotification(envelope);
        assertThat(seen).containsExactly(conv);
    }
}
