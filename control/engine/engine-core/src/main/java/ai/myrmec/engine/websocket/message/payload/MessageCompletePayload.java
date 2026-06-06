package ai.myrmec.engine.websocket.message.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload for {@code message.complete} (Agent → Engine).
 *
 * <p>Final marker for a streamed assistant turn. Carries the full
 * canonical text (sum of all {@link MessageDeltaPayload} chunks) so
 * the engine can persist a {@code ConversationMessage} row even if a
 * late-arriving viewer reconnected after the deltas finished
 * streaming.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageCompletePayload {

    /** Conversation this turn belongs to. */
    private UUID conversationId;

    /** Pre-allocated sequence_no for this assistant turn. */
    private long sequenceNo;

    /** Full assistant text — engine writes this to {@code content}. */
    private String content;

    /** Model that produced the turn (informational). */
    private String modelCode;

    /** Total tokens consumed by the model call(s) that produced the turn. */
    private Integer tokenCount;
}
