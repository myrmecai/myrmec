package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Payload for {@code agent.bind} (Engine → Agent). The engine has
 * reserved this warm worker for a conversation and pinned a profile
 * version; the worker loads that conversation context and serves it
 * (agent-concurrency §9.5).
 */
@Data
@Builder
public class AgentBindPayload {

    /** The conversation this worker is now bound to. */
    private UUID conversationId;

    /** The agent profile version pinned at reserve-time. */
    private UUID profileVersionId;

    /**
     * The home node (replica) the worker must open its conversation socket
     * to (agent-concurrency §9.4). {@code homeNodeId} is the stable node id
     * recorded on {@code agents.home_node_id}; {@code homeNodeAddr} is the
     * in-cluster {@code host:port} the worker dials directly (co-location
     * keeps the token stream node-local). On a single node both resolve to
     * "self".
     */
    private String homeNodeId;

    /** In-cluster address ({@code host:port}) of the home node to dial. */
    private String homeNodeAddr;
}
