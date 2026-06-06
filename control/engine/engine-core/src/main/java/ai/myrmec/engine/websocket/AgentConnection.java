package ai.myrmec.engine.websocket;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an active WebSocket connection to an agent instance.
 */
@Data
@Builder
public class AgentConnection {
    
    /** Agent instance ID (from registration) */
    private UUID agentInstanceId;
    
    /** Agent name for logging */
    private String agentName;
    
    /** WebSocket session */
    private WebSocketSession session;
    
    /** When connection was established */
    private Instant connectedAt;
    
    /** Last pong received (for heartbeat tracking) */
    private Instant lastPongAt;
    
    /** Current task ID (null if idle) */
    private UUID currentTaskId;
    
    /** When current task was assigned */
    private Instant taskAssignedAt;
    
    /**
     * Check if agent is idle (no current task).
     */
    public boolean isIdle() {
        return currentTaskId == null;
    }
    
    /**
     * Check if task assignment has timed out (5 second accept window).
     */
    public boolean isTaskAssignmentTimedOut() {
        if (taskAssignedAt == null) {
            return false;
        }
        return Instant.now().isAfter(taskAssignedAt.plusSeconds(5));
    }
}
