package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Payload for {@code agent.release} (Engine → Agent). The engine is
 * tearing down this worker's binding; the worker drops its conversation
 * context and returns to the warm pool (agent-concurrency §9.5).
 */
@Data
@Builder
public class AgentReleasePayload {

    /** The conversation whose binding is being released. */
    private UUID conversationId;

    /** Optional human-readable reason (e.g. {@code "turn complete"}, {@code "cancelled"}). */
    private String reason;
}
