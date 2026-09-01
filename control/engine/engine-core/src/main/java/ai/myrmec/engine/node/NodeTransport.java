// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.message.WebSocketMessage;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Unified cross-node transport SPI (agent-concurrency §9.8).
 *
 * <p>All inter-instance communication — both point-to-point control frames
 * and fan-out streaming frames — routes through this single seam. The OSS
 * implementation ({@link DirectRpcNodeTransport}) uses direct HTTP RPC for
 * both paths; EE deployments can provide a Redis-backed bean that handles
 * both patterns through a single integration point.</p>
 *
 * <h3>Two delivery patterns, one SPI</h3>
 * <ul>
 *   <li><b>Point-to-point</b> ({@link #sendToNode}): deliver a control
 *       frame to a specific agent instance on a specific home node.
 *       Returns {@code true} if the frame was handed off for delivery.</li>
 *   <li><b>Fan-out</b> ({@link #publishToPeers}): deliver a streaming
 *       frame to all peer instances that have subscribers for a
 *       conversation. Fire-and-forget — local delivery already happened.</li>
 * </ul>
 *
 * <h3>EE integration</h3>
 * <p>EE provides a single {@code NodeTransport} bean (e.g. Redis pub/sub
 * for fan-out + Redis reliable queue for point-to-point). The
 * {@code @ConditionalOnMissingBean(NodeTransport.class)} guard on the OSS
 * {@link DirectRpcNodeTransport} yields when an EE bean is present.</p>
 *
 * <h3>Revert to old split SPIs</h3>
 * <p>To revert: restore {@code AgentTransport} + {@code ConversationStreamFanout}
 * as separate interfaces, restore their respective implementations, and
 * update the three call sites ({@code AgentWebSocketHandler.route()},
 * {@code ConversationStreamBroker.broadcast()},
 * {@code ConversationTurnDispatcher}) to use the split interfaces.</p>
 */
public interface NodeTransport {

    /**
     * Point-to-point: deliver a control message to {@code agentInstanceId},
     * whose conversation socket is homed on {@code homeNodeId}.
     *
     * @param homeNodeId      replica that owns the worker's socket; {@code null}
     *                        (unbound worker) or this replica's own id means
     *                        deliver in-process
     * @param agentInstanceId target worker
     * @param message         control message to deliver
     * @return {@code true} if the message was handed off for delivery
     */
    boolean sendToNode(String homeNodeId, UUID agentInstanceId, WebSocketMessage<?> message);

    /**
     * Fan-out: publish a streaming frame so peer instances can deliver it
     * to their local SSE subscribers. The local instance must have already
     * delivered to its own subscribers before calling — this method is
     * only the "tell everyone else" half.
     *
     * <p>Implementations MUST be safe to call from many threads and MUST
     * filter out frames that originated from this instance to avoid
     * self-delivery loops.</p>
     */
    void publishToPeers(UUID conversationId, String jsonFrame);

    /**
     * Wire the callback used when a streaming frame arrives from a peer
     * instance. The broker registers a handler that performs ONLY local
     * delivery (no further {@link #publishToPeers}) to avoid amplification
     * storms.
     *
     * <p>Implementations should call the handler at most once per remote
     * frame and MUST NOT call it for frames they themselves published.</p>
     */
    void setFanoutHandler(BiConsumer<UUID, String> handler);
}