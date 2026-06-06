package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.cancel message (Engine → Agent).
 */
@Data
@Builder
public class TaskCancelPayload {
    
    /** Task to cancel */
    private UUID taskId;
    
    /** Reason for cancellation */
    private String reason;
}
