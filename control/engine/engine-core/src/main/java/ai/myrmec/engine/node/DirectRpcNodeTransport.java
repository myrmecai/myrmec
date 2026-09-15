// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * OSS {@link NodeTransport} implementation using direct HTTP RPC for
 * fan-out stream delivery.
 *
 * <p><b>Unified protocol (P6-T6):</b> the legacy point-to-point leg
 * ({@code sendToNode} → {@code AgentConnectionManager}) died with the legacy
 * agent wire — the unified host socket is the only control channel, and a
 * replica that loses a host's socket reconciles through session state, not
 * by relaying frames to a peer. Only the SSE fan-out half remains: it iterates
 * all UP peer nodes and POSTs the frame to each one's
 * {@code /api/v1/internal/stream-relay} endpoint. The local instance is
 * skipped (local delivery already happened in the broker). Fan-out is
 * fire-and-forget — a single unreachable peer does not block the turn.</p>
 */
@Component
@Slf4j
public class DirectRpcNodeTransport implements NodeTransport {

    private final EngineNodeRepository nodeRepository;
    private final NodeRegistryService nodeRegistry;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private final boolean relayEnabled;
    private final String relaySecret;

    private volatile BiConsumer<UUID, String> fanoutHandler = (id, frame) -> { };

    public DirectRpcNodeTransport(
            EngineNodeRepository nodeRepository,
            NodeRegistryService nodeRegistry,
            ObjectMapper objectMapper,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.nodeRepository = nodeRepository;
        this.nodeRegistry = nodeRegistry;
        this.objectMapper = objectMapper;
        this.relayEnabled = relayEnabled;
        this.relaySecret = relaySecret;
        this.restClient = RestClient.create();
    }

    // ── Fan-out ─────────────────────────────────────────────────────

    @Override
    public void setFanoutHandler(BiConsumer<UUID, String> handler) {
        this.fanoutHandler = handler == null ? (id, frame) -> { } : handler;
    }

    @Override
    public void publishToPeers(UUID conversationId, String jsonFrame) {
        if (!relayEnabled || relaySecret.isBlank()) {
            return; // single-instance: nothing to do
        }
        List<EngineNode> peers = nodeRepository.findByStatus(EngineNode.Status.UP);
        for (EngineNode peer : peers) {
            if (peer.getNodeId().equals(nodeRegistry.getSelfNodeId())) {
                continue; // local delivery already happened
            }
            try {
                postToPeer(peer.getAddress(),
                        "/api/v1/internal/stream-relay",
                        new StreamRelayRequest(conversationId, jsonFrame));
            } catch (Exception e) {
                // Fan-out is best-effort: a single unreachable peer must not
                // block the turn. Local delivery already happened.
                log.debug("Stream relay to peer {} for conv {} failed: {}",
                        peer.getNodeId(), conversationId, e.getMessage());
            }
        }
    }

    /**
     * Handle an inbound stream relay from a peer. Called by
     * {@link StreamRelayController}. Delivers the frame to local SSE
     * subscribers via the fanout handler.
     */
    public void handleStreamRelay(UUID conversationId, String frameJson) {
        fanoutHandler.accept(conversationId, frameJson);
    }

    // ── Shared HTTP plumbing ───────────────────────────────────────

    /**
     * POST a relay request to a peer's internal endpoint. Extracted so it
     * can be exercised without a live peer in tests.
     */
    protected boolean postToPeer(String peerAddress, String path, Object request) {
        try {
            Boolean delivered = restClient.post()
                    .uri("http://{addr}" + path, peerAddress)
                    .header(StreamRelayController.NODE_SECRET_HEADER, relaySecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(delivered);
        } catch (Exception e) {
            log.warn("Relay POST to peer {} failed: {}", peerAddress, e.getMessage());
            return false;
        }
    }
}