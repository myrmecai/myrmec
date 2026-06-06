package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.reject message (Agent → Engine).
 */
@Data
public class TaskRejectPayload {
    
    /** Rejected task ID */
    private UUID taskId;
    
    /** Reason for rejection */
    private String reason;
}
