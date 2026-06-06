package ai.myrmec.engine.websocket.message.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload for {@code task.cancelled} (Agent → Engine).
 *
 * <p>Acknowledgement of a {@link TaskCancelPayload}. The agent stops
 * streaming, releases resources, then emits this so the engine can mark
 * the task CANCELLED and fan a final {@code task.cancelled} status frame
 * to viewers. Optional {@code partialContent} carries whatever text had
 * already been generated, useful for transcripts.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCancelledPayload {

    /** The cancelled task. */
    private UUID taskId;

    /**
     * Optional conversation context — set if the cancelled task belonged
     * to a CONVERSATIONAL session.
     */
    private UUID conversationId;

    /** Pre-allocated sequence_no for the in-flight assistant turn, if any. */
    private Long sequenceNo;

    /** Optional: text generated before cancellation took effect. */
    private String partialContent;

    /** Reason text echoed from the cancel request, or "user_request". */
    private String reason;
}
