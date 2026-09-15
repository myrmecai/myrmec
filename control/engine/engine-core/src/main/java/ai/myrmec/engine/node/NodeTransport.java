// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.message.WebSocketMessage;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Unified cross-node transport SPI (agent-concurrency §9.8).
 *
 * <p>All inter-instance communication routes through this single seam.
 * The OSS implementation ({@link DirectRpcNodeTransport}) uses direct
 * HTTP RPC; EE deployments can provide a Redis-backed bean that handles
 * both patterns through a single integration point.</p>
 *
 * <h3>Delivery pattern</h3>
 * <ul>
 *   <li><b>Fan-out</b> ({@link #publishToPeers}): deliver a streaming
 *       frame to all peer instances that have subscribers for a
 *       conversation. Fire-and-forget — local delivery already
 *       happened.</li>
 * </ul>
 *
 * <p><b>Unified protocol (P6-T6):</b> the legacy point-to-point control
 * leg ({@code sendToNode}) died with the legacy agent wire — a replica
 * that loses a host socket reconciles through durable session state, so
 * there is no control-frame relay to re-implement.</p>
 *
 * <h3>EE integration</h3>
 * <p>EE provides a single {@code NodeTransport} bean (e.g. Redis pub/sub
 * for fan-out). The {@code @ConditionalOnMissingBean(NodeTransport.class)}
 * guard on the OSS {@link DirectRpcNodeTransport} yields when an EE
 * bean is present.</p>
 */
public interface NodeTransport {

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