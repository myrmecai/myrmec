package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Payload for task.complete message (Agent → Engine).
 */
@Data
public class TaskCompletePayload {
    
    /** Completed task ID */
    private UUID taskId;
    
    /** Attempt ID for this completion */
    private UUID attemptId;
    
    /** Task result */
    private Map<String, Object> result;
}
