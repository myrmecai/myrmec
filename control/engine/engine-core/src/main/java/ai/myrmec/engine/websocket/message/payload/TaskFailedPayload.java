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

    /**
     * Phase 10 #71 — when {@link #errorCode} is
     * {@code MODEL_RATE_LIMITED}, hints how long the engine should
     * wait before re-dispatching. Sourced from the upstream provider's
     * {@code Retry-After} header by the agent SDK. Ignored for other
     * error codes.
     */
    private Integer retryAfterSeconds;
}
