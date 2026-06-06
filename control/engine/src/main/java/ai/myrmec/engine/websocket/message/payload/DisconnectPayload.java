package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

/**
 * Payload for disconnect message (Agent → Engine).
 * Sent before graceful shutdown.
 */
@Data
public class DisconnectPayload {
    
    /** Reason for disconnection */
    private String reason;
}
