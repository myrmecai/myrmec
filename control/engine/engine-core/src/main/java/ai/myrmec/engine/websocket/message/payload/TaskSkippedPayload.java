package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;
import java.util.UUID;

/**
 * Payload for task.skipped message (Agent → Engine).
 * Indicates task was not executed (e.g., precondition not met).
 * Skipped attempts do not count toward retry limits.
 */
@Data
public class TaskSkippedPayload {
    
    /** Skipped task ID */
    private UUID taskId;
    
    /** Attempt ID that was skipped */
    private UUID attemptId;
    
    /** Reason for skipping the task */
    private String reason;
}
