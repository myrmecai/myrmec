package ai.myrmec.engine.websocket.message.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload for {@code conversation.turn.cancel} (Engine → Agent).
 *
 * <p>Tells the bound worker to abort the in-flight assistant turn for a
 * conversation. The worker breaks out of its streaming / tool loop and
 * acknowledges with {@code task.cancelled}, carrying any partial text.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurnCancelPayload {

    /** The conversation whose in-flight turn should be cancelled. */
    private UUID conversationId;

    /** Reason text, echoed back on the acknowledgement. */
    private String reason;
}
