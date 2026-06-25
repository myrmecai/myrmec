package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.Map;

/**
 * Payload for {@code host.announce} (Agent Host → Engine). The Supervisor
 * advertises its installed capabilities and the capacity it auto-sized from.
 * The host is the source of truth, so the engine overwrites the AgentHost's
 * values on every announce.
 */
@Data
public class HostAnnouncePayload {

    /** Tools + runtime physically installed on the host: {@code { tools[], runtime[] }}. */
    private Map<String, Object> provisions;

    /** CPU/RAM the Supervisor auto-sized its warm pool from. */
    private Map<String, Object> reportedCapacity;
}
