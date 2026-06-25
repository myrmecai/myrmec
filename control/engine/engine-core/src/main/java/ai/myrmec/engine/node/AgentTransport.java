package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.message.WebSocketMessage;

import java.util.UUID;

/**
 * Cross-node delivery SPI (slice 4b, agent-concurrency §9.8). All control
 * messaging to a worker is routed through this seam so it works regardless of
 * which replica currently holds the worker's conversation socket.
 *
 * <p>The OSS implementation ({@link DirectRpcAgentTransport}) short-circuits
 * to the in-process socket registry when the worker is homed on this replica
 * (the only case on a single node), and relays over direct node-to-node RPC
 * otherwise. Envelopes carry IDs only — never live objects — so the same
 * message serializes identically on either side of the hop.</p>
 */
public interface AgentTransport {

    /**
     * Deliver a control message to {@code agentInstanceId}, whose conversation
     * socket is homed on {@code homeNodeId}.
     *
     * @param homeNodeId      replica that owns the worker's socket; {@code null}
     *                        (unbound worker) or this replica's own id means
     *                        deliver in-process
     * @param agentInstanceId target worker
     * @param message         control message to deliver
     * @return {@code true} if the message was handed off for delivery
     */
    boolean sendToNode(String homeNodeId, UUID agentInstanceId, WebSocketMessage<?> message);
}
