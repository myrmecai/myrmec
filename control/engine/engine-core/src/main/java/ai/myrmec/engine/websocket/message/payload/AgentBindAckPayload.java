package ai.myrmec.engine.websocket.message.payload;

import lombok.Data;

import java.util.UUID;

/**
 * Payload for {@code agent.bind.ack} and {@code agent.bind.nack}
 * (Agent Host → Engine). The host echoes the {@code conversationId} from the
 * originating {@link AgentBindPayload} so the engine can match the handshake
 * to the reserved worker. On ack the engine advances the worker to
 * {@code CONNECTING}; on nack it releases the worker to {@code IDLE}, using
 * the optional {@code reason} for diagnostics (agent-concurrency §9.5).
 */
@Data
public class AgentBindAckPayload {

    /** The conversation this bind handshake refers to. */
    private UUID conversationId;

    /** Optional human-readable reason (populated on nack). */
    private String reason;
}
