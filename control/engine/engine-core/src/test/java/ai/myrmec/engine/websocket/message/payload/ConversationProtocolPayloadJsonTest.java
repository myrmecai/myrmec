package ai.myrmec.engine.websocket.message.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6b — verifies the new Agent⇄Engine payload types round-trip
 * cleanly through Jackson. The handler that consumes them is exercised
 * via integration tests in later phases (6c will land a full WebSocket
 * end-to-end test); for now this is the cheap regression net catching
 * field renames + nullable mistakes.
 */
class ConversationProtocolPayloadJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void messageDeltaRoundTrip() throws Exception {
        UUID conversationId = UUID.randomUUID();
        MessageDeltaPayload delta = MessageDeltaPayload.builder()
                .conversationId(conversationId)
                .sequenceNo(7L)
                .deltaIndex(0L)
                .content("Hello, ")
                .build();

        String json = mapper.writeValueAsString(delta);
        MessageDeltaPayload parsed = mapper.readValue(json, MessageDeltaPayload.class);

        assertThat(parsed.getConversationId()).isEqualTo(conversationId);
        assertThat(parsed.getSequenceNo()).isEqualTo(7L);
        assertThat(parsed.getDeltaIndex()).isEqualTo(0L);
        assertThat(parsed.getContent()).isEqualTo("Hello, ");
    }

    @Test
    void messageCompleteRoundTripWithNullableTokenCount() throws Exception {
        UUID conversationId = UUID.randomUUID();
        MessageCompletePayload complete = MessageCompletePayload.builder()
                .conversationId(conversationId)
                .sequenceNo(7L)
                .content("Hello, world.")
                .modelCode("github-gpt-4o")
                .tokenCount(null)
                .build();

        String json = mapper.writeValueAsString(complete);
        MessageCompletePayload parsed = mapper.readValue(json, MessageCompletePayload.class);

        assertThat(parsed.getConversationId()).isEqualTo(conversationId);
        assertThat(parsed.getSequenceNo()).isEqualTo(7L);
        assertThat(parsed.getContent()).isEqualTo("Hello, world.");
        assertThat(parsed.getModelCode()).isEqualTo("github-gpt-4o");
        assertThat(parsed.getTokenCount()).isNull();
    }
}
