package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.progress message (Agent → Engine).
 */
@Data
public class TaskProgressPayload {
    
    /** Task ID */
    private UUID taskId;
    
    /** Attempt ID for this progress */
    private UUID attemptId;
    
    /** Progress percentage (0-100) */
    private int progress;
    
    /** Optional progress message */
    private String message;
}
