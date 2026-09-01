// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
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
 * OSS {@link NodeTransport} implementation using direct HTTP RPC for both
 * point-to-point control relay and fan-out stream delivery.
 *
 * <p><b>In-process short-circuit:</b> when the worker is homed on this
 * replica — {@code homeNodeId} is {@code null} (unbound) or equals this
 * node's id — the message is delivered straight to the local socket
 * registry.</p>
 *
 * <p><b>Direct-RPC relay:</b> otherwise the frame is serialized once and
 * POSTed to the owning peer replica's relay endpoint. The relay leg is
 * <i>opt-in</i>: it only activates when {@code myrmec.node.relay.enabled}
 * is set and a shared secret is configured, so a single-node OSS deployment
 * never attempts a network hop.</p>
 *
 * <p><b>Fan-out:</b> iterates all UP peer nodes and POSTs the frame to
 * each one's {@code /api/v1/internal/stream-relay} endpoint. The local
 * instance is skipped (local delivery already happened in the broker).
 * Fan-out is fire-and-forget — a single unreachable peer does not block
 * the turn.</p>
 */
@Component
@Slf4j
public class DirectRpcNodeTransport implements NodeTransport {

    private final AgentConnectionManager connectionManager;
    private final EngineNodeRepository nodeRepository;
    private final NodeRegistryService nodeRegistry;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private final boolean relayEnabled;
    private final String relaySecret;

    private volatile BiConsumer<UUID, String> fanoutHandler = (id, frame) -> { };

    public DirectRpcNodeTransport(
            AgentConnectionManager connectionManager,
            EngineNodeRepository nodeRepository,
            NodeRegistryService nodeRegistry,
            ObjectMapper objectMapper,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.connectionManager = connectionManager;
        this.nodeRepository = nodeRepository;
        this.nodeRegistry = nodeRegistry;
        this.objectMapper = objectMapper;
        this.relayEnabled = relayEnabled;
        this.relaySecret = relaySecret;
        this.restClient = RestClient.create();
    }

    // ── Point-to-point ──────────────────────────────────────────────

    @Override
    public boolean sendToNode(String homeNodeId, UUID agentInstanceId, WebSocketMessage<?> message) {
        if (isLocal(homeNodeId)) {
            return connectionManager.sendMessage(agentInstanceId, message);
        }
        return relayToPeer(homeNodeId, agentInstanceId, message);
    }

    private boolean isLocal(String homeNodeId) {
        return homeNodeId == null
                || homeNodeId.isBlank()
                || homeNodeId.equals(nodeRegistry.getSelfNodeId());
    }

    private boolean relayToPeer(String homeNodeId, UUID agentInstanceId, WebSocketMessage<?> message) {
        if (!relayEnabled || relaySecret.isBlank()) {
            log.warn("Worker {} is homed on peer node {} but node relay is disabled; dropping {} frame",
                    agentInstanceId, homeNodeId, message.getType());
            return false;
        }
        EngineNode peer = nodeRepository.findById(homeNodeId).orElse(null);
        if (peer == null || peer.getStatus() == EngineNode.Status.DOWN) {
            log.warn("Cannot relay {} frame to worker {}: home node {} is unknown or DOWN",
                    message.getType(), agentInstanceId, homeNodeId);
            return false;
        }
        String frameJson;
        try {
            frameJson = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} frame for relay to node {}: {}",
                    message.getType(), homeNodeId, e.getMessage());
            return false;
        }
        return postToPeer(peer.getAddress(),
                "/api/v1/internal/agent-relay",
                new AgentRelayRequest(agentInstanceId, frameJson));
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
                    .header(AgentRelayController.NODE_SECRET_HEADER, relaySecret)
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