package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for task.status_response message (Agent → Engine).
 * Sent in response to task.status_request.
 */
@Data
public class TaskStatusResponsePayload {
    
    /** Task ID */
    private UUID taskId;
    
    /** Attempt ID */
    private UUID attemptId;
    
    /**
     * Current status of the task at the agent:
     * - "running" - still executing
     * - "completed" - finished successfully (result in output)
     * - "failed" - failed with error
     * - "unknown" - agent doesn't know about this task
     */
    private String status;
    
    /** Current progress (0-100), only if status is "running" */
    private Integer progress;
    
    /** Result if status is "completed" */
    private Map<String, Object> result;
    
    /** Error message if status is "failed" */
    private String error;
    
    /** Error code if status is "failed" */
    private String errorCode;
}
