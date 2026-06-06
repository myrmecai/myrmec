package ai.myrmec.engine.websocket.message.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload for {@code message.delta} (Agent → Engine).
 *
 * <p>Carries one chunk of a streamed assistant turn. The engine
 * forwards each delta verbatim to all WebSocket viewers (Phase 6c) and
 * defers persistence until {@link MessageCompletePayload} arrives.</p>
 *
 * <p>{@code sequenceNo} is the conversation-message sequence number
 * pre-allocated by the engine when the user turn was accepted, so
 * downstream viewers can stitch a partial turn back together.
 * {@code deltaIndex} is a monotonic per-turn counter (0-based) used
 * only by the broker to drop out-of-order frames.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDeltaPayload {

    /** Conversation this delta belongs to. */
    private UUID conversationId;

    /**
     * The (pre-allocated) sequence_no for the assistant message being
     * streamed. Stable across all deltas of the same turn.
     */
    private long sequenceNo;

    /** 0-based delta index within this turn. */
    private long deltaIndex;

    /** Chunk text (model output token / token group). */
    private String content;
}
