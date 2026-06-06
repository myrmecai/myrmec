package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

/**
 * Payload for task.status_request message (Engine → Agent).
 * Sent when engine needs to verify task status (e.g., after agent reconnect).
 */
@Data
@Builder
public class TaskStatusRequestPayload {
    
    /** Task ID to check status for */
    private UUID taskId;
    
    /** Attempt ID that was assigned to this agent */
    private UUID attemptId;
}
