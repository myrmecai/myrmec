package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.accept message (Agent → Engine).
 */
@Data
public class TaskAcceptPayload {
    
    /** Accepted task ID */
    private UUID taskId;
}
