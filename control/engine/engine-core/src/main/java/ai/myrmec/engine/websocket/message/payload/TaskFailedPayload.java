package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for task.failed message (Agent → Engine).
 */
@Data
public class TaskFailedPayload {
    
    /** Failed task ID */
    private UUID taskId;
    
    /** Attempt ID for this failure */
    private UUID attemptId;
    
    /** Error message */
    private String error;
    
    /** Optional error code */
    private String errorCode;
}
