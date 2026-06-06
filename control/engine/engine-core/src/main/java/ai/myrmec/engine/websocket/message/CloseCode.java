package ai.myrmec.engine.websocket.message;

import org.springframework.web.socket.CloseStatus;

/**
 * Custom WebSocket close codes for agent communication.
 */
public final class CloseCode {
    
    private CloseCode() {}
    
    /** Normal closure - agent disconnected gracefully */
    public static final CloseStatus NORMAL = CloseStatus.NORMAL;
    
    /** Engine shutdown */
    public static final CloseStatus GOING_AWAY = CloseStatus.GOING_AWAY;
    
    /** Token expired - agent should refresh and reconnect */
    public static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4001, "Token expired");
    
    /** Invalid token - agent needs to re-register */
    public static final CloseStatus INVALID_TOKEN = new CloseStatus(4002, "Invalid token");
    
    /** Agent deactivated by admin */
    public static final CloseStatus AGENT_DEACTIVATED = new CloseStatus(4003, "Agent deactivated");
    
    /** Duplicate connection - another instance connected with same token */
    public static final CloseStatus DUPLICATE_CONNECTION = new CloseStatus(4004, "Duplicate connection");
}
