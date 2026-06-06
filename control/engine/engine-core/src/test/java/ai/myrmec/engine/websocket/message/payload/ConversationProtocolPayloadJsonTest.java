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

    @Test
    void taskCancelledRoundTripWithOptionalConversationContext() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        TaskCancelledPayload cancelled = TaskCancelledPayload.builder()
                .taskId(taskId)
                .conversationId(conversationId)
                .sequenceNo(12L)
                .partialContent("Hello, w...")
                .reason("user_request")
                .build();

        String json = mapper.writeValueAsString(cancelled);
        TaskCancelledPayload parsed = mapper.readValue(json, TaskCancelledPayload.class);

        assertThat(parsed.getTaskId()).isEqualTo(taskId);
        assertThat(parsed.getConversationId()).isEqualTo(conversationId);
        assertThat(parsed.getSequenceNo()).isEqualTo(12L);
        assertThat(parsed.getPartialContent()).isEqualTo("Hello, w...");
        assertThat(parsed.getReason()).isEqualTo("user_request");
    }

    @Test
    void taskCancelledRoundTripForOneShotTaskOmitsConversationFields() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskCancelledPayload cancelled = TaskCancelledPayload.builder()
                .taskId(taskId)
                .reason("timeout")
                .build();

        String json = mapper.writeValueAsString(cancelled);
        TaskCancelledPayload parsed = mapper.readValue(json, TaskCancelledPayload.class);

        assertThat(parsed.getTaskId()).isEqualTo(taskId);
        assertThat(parsed.getConversationId()).isNull();
        assertThat(parsed.getSequenceNo()).isNull();
        assertThat(parsed.getPartialContent()).isNull();
        assertThat(parsed.getReason()).isEqualTo("timeout");
    }
}
