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

import java.util.UUID;

/**
 * OSS {@link AgentTransport} (slice 4b, agent-concurrency §9.8).
 *
 * <p><b>In-process short-circuit:</b> when the worker is homed on this replica
 * — {@code homeNodeId} is {@code null} (unbound) or equals this node's id (the
 * only case on a single node) — the message is delivered straight to the local
 * socket registry, byte-identical to the pre-4b path.</p>
 *
 * <p><b>Direct-RPC relay:</b> otherwise the frame is serialized once and POSTed
 * to the owning peer replica's node-relay endpoint. The relay leg is
 * <i>opt-in</i>: it only activates when {@code myrmec.node.relay.enabled} is set
 * and a shared secret is configured, so a single-node OSS deployment never
 * attempts a network hop.</p>
 */
@Component
@Slf4j
public class DirectRpcAgentTransport implements AgentTransport {

    private final AgentConnectionManager connectionManager;
    private final EngineNodeRepository nodeRepository;
    private final NodeRegistryService nodeRegistry;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private final boolean relayEnabled;
    private final String relaySecret;

    public DirectRpcAgentTransport(
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

    @Override
    public boolean sendToNode(String homeNodeId, UUID agentInstanceId, WebSocketMessage<?> message) {
        if (isLocal(homeNodeId)) {
            return connectionManager.sendMessage(agentInstanceId, message);
        }
        return relayToPeer(homeNodeId, agentInstanceId, message);
    }

    /** A worker with no home node, or homed on this replica, is delivered locally. */
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
        return postToPeer(peer.getAddress(), new AgentRelayRequest(agentInstanceId, frameJson));
    }

    /**
     * Performs the actual node-to-node POST. Extracted so it can be exercised
     * without a live peer in tests.
     */
    protected boolean postToPeer(String peerAddress, AgentRelayRequest request) {
        try {
            Boolean delivered = restClient.post()
                    .uri("http://{addr}/api/v1/internal/agent-relay", peerAddress)
                    .header(AgentRelayController.NODE_SECRET_HEADER, relaySecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(delivered);
        } catch (Exception e) {
            log.warn("Relay POST to peer {} for worker {} failed: {}",
                    peerAddress, request.agentInstanceId(), e.getMessage());
            return false;
        }
    }
}
