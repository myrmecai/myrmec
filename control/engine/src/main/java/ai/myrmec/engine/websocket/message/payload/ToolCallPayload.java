package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Payload for tool.call message (Agent → Engine).
 * Sent when agent invokes an LLM tool.
 */
@Data
public class ToolCallPayload {
    
    /** Task ID */
    private UUID taskId;
    
    /** Attempt ID for this tool call */
    private UUID attemptId;
    
    /** Tool name being invoked */
    private String toolName;
    
    /** Unique call ID for correlation with result */
    private String callId;
    
    /** Tool input parameters */
    private Map<String, Object> input;
}
