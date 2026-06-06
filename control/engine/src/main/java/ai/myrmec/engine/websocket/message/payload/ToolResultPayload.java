package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Payload for tool.result message (Agent → Engine).
 * Sent when tool invocation completes.
 */
@Data
public class ToolResultPayload {
    
    /** Task ID */
    private UUID taskId;
    
    /** Attempt ID for this tool result */
    private UUID attemptId;
    
    /** Call ID matching tool.call */
    private String callId;
    
    /** Tool output */
    private Map<String, Object> output;
    
    /** Execution duration in milliseconds */
    private long durationMs;
    
    /** Error message if tool failed (null for success) */
    private String error;
}
