package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for log message (Agent → Engine).
 */
@Data
public class LogPayload {
    
    /** Task ID (optional - for non-task logs) */
    private UUID taskId;
    
    /** Attempt ID for task-related logs */
    private UUID attemptId;
    
    /** Log level: DEBUG, INFO, WARN, ERROR */
    private String level;
    
    /** Log message */
    private String message;
    
    /** Optional structured data */
    private Map<String, Object> data;
    
    /** Timestamp when log was generated (defaults to receipt time if not provided) */
    private Instant timestamp;

    /** Source of the log: TASK (default), AGENT, or SYSTEM */
    private String source;
}
