// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.websocket.host.ChannelConnectionRegistry;
import ai.myrmec.engine.websocket.host.HostConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.UUID;

/**
 * Inter-node host-socket routing (protocol §20, decision H5): resolves the
 * replica that owns a host instance's socket and either delivers locally
 * (same node) or relays the frame over the HTTP mesh to the owning peer.
 *
 * <p><b>Owner identity</b> comes from {@code agent_host_instances}.
 * control_node_id} — stamped at {@code host.open} from the same
 * {@code NodeRegistryService.getSelfNodeId()} source that
 * {@code HostOpenedPayload.serverNodeId} reports to the host, so the wire
 * field and the routing field are one value by construction.</p>
 *
 * <p><b>Peer address resolution</b> reuses the existing replica registry: the
 * peer's {@code engine_nodes.address} (pod dial address), exactly what
 * {@link DirectRpcNodeTransport#publishToPeers} iterates. A peer not in the
 * registry (or marked DOWN) has no relayable address.</p>
 *
 * <p><b>Failure semantics:</b> on relay failure (peer unreachable, 404, socket
 * missing on the peer, timeout) the send falls back to a local socket when
 * this replica happens to hold one — dev/topology misconfig tolerance — and
 * otherwise surfaces the failure to the caller, matching the existing
 * engine→host send-failure semantics (callers log and reconcile through
 * durable state).</p>
 */
@Component
@Slf4j
public class HostFrameRelayService {

    private final AgentHostInstanceRepository instanceRepository;
    private final EngineNodeRepository nodeRepository;
    private final NodeRegistryService nodeRegistry;
    private final HostConnectionManager connectionManager;
    private final ChannelConnectionRegistry channelRegistry;
    private final RestClient restClient;

    private final boolean relayEnabled;
    private final String relaySecret;

    public HostFrameRelayService(
            AgentHostInstanceRepository instanceRepository,
            EngineNodeRepository nodeRepository,
            NodeRegistryService nodeRegistry,
            HostConnectionManager connectionManager,
            ChannelConnectionRegistry channelRegistry,
            @Value("${myrmec.node.relay.enabled:false}") boolean relayEnabled,
            @Value("${myrmec.node.relay.secret:}") String relaySecret) {
        this.instanceRepository = instanceRepository;
        this.nodeRepository = nodeRepository;
        this.nodeRegistry = nodeRegistry;
        this.connectionManager = connectionManager;
        this.channelRegistry = channelRegistry;
        this.relayEnabled = relayEnabled;
        this.relaySecret = relaySecret;
        this.restClient = RestClient.create();
    }

    /**
     * Send an engine→host frame to the instance that owns {@code sessionId}.
     * Local instance → direct socket send (channel-first, §7.5). Remote
     * instance → HTTP relay; on relay failure, fall back to a local socket if
     * one exists, else report failure.
     *
     * @return {@code true} when the frame reached the owning socket (locally
     *         or via a peer that accepted it)
     */
    public boolean send(UUID sessionId, UUID hostInstanceId, String envelopeJson) {
        return route(sessionId, hostInstanceId, envelopeJson, true);
    }

    /**
     * Control-socket-only variant (§7.1): a {@code session.offer} precedes any
     * channel binding by definition, so the channel registry is never
     * consulted — the offer goes to the owning replica's control socket.
     */
    public boolean sendControl(UUID hostInstanceId, String envelopeJson) {
        return route(null, hostInstanceId, envelopeJson, false);
    }

    private boolean route(UUID sessionId, UUID hostInstanceId, String envelopeJson,
                          boolean channelFirst) {
        String ownerNode = nodeOf(hostInstanceId);
        if (ownerNode == null || isSelf(ownerNode)) {
            // Unknown owner (legacy row / instance gone): behave exactly like
            // the pre-relay local send — try the local socket, report failure.
            return sendLocal(sessionId, hostInstanceId, envelopeJson, channelFirst);
        }
        return relayOrFallback(sessionId, hostInstanceId, ownerNode, envelopeJson, channelFirst);
    }

    /**
     * Local delivery: dedicated session channel when bound and open (§7.5),
     * else the control socket. Used directly on the self path and as the
     * relay-failure fallback.
     */
    public boolean sendLocal(UUID sessionId, UUID hostInstanceId, String envelopeJson) {
        return sendLocal(sessionId, hostInstanceId, envelopeJson, true);
    }

    private boolean sendLocal(UUID sessionId, UUID hostInstanceId, String envelopeJson,
                              boolean channelFirst) {
        Optional<WebSocketSession> socket = resolveLocalSocket(sessionId, hostInstanceId, channelFirst);
        if (socket.isEmpty()) {
            return false;
        }
        try {
            socket.get().sendMessage(new TextMessage(envelopeJson));
            return true;
        } catch (Exception e) {
            log.warn("Failed local host send to instance {}: {}", hostInstanceId, e.getMessage());
            return false;
        }
    }

    /**
     * The owning node id of a host instance's socket — the same value
     * {@code host.opened.serverNodeId} carried to the host at open time.
     * Empty when the instance row no longer exists.
     */
    public Optional<String> ownerNodeOf(UUID hostInstanceId) {
        return Optional.ofNullable(nodeOf(hostInstanceId));
    }

    /**
     * §20 relay half: POST the opaque frame to the owning peer's
     * {@code /api/v1/internal/agent-relay}. On any failure (peer down, bad
     * secret, 404/no socket there, timeout) fall back to the local socket if
     * this replica holds one — single-node dev tolerates topology misconfig —
     * and otherwise fail the send.
     */
    private boolean relayOrFallback(UUID sessionId, UUID hostInstanceId,
                                    String ownerNode, String envelopeJson, boolean channelFirst) {
        if (!relayEnabled || relaySecret.isBlank()) {
            log.debug("Relay disabled — host instance {} lives on node {} but this "
                    + "replica cannot relay; trying local socket", hostInstanceId, ownerNode);
            return sendLocal(sessionId, hostInstanceId, envelopeJson, channelFirst);
        }
        Optional<String> peerAddress = addressOf(ownerNode);
        if (peerAddress.isEmpty()) {
            log.warn("No relay address for node {} (instance {}) — falling back to local send",
                    ownerNode, hostInstanceId);
            return sendLocal(sessionId, hostInstanceId, envelopeJson, channelFirst);
        }
        boolean delivered = postRelay(peerAddress.get(), hostInstanceId, envelopeJson);
        if (delivered) {
            return true;
        }
        log.warn("Relay to node {} for instance {} failed — falling back to local send",
                ownerNode, hostInstanceId);
        return sendLocal(sessionId, hostInstanceId, envelopeJson, channelFirst);
    }

    /** POST to a peer's internal relay endpoint. Overridable seam for tests. */
    protected boolean postRelay(String peerAddress, UUID targetInstanceId, String frameJson) {
        try {
            Boolean delivered = restClient.post()
                    .uri("http://{addr}/api/v1/internal/agent-relay", peerAddress)
                    .header(AgentRelayController.NODE_SECRET_HEADER, relaySecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AgentRelayRequest(targetInstanceId, frameJson))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(delivered);
        } catch (Exception e) {
            log.warn("Relay POST to peer {} for instance {} failed: {}",
                    peerAddress, targetInstanceId, e.getMessage());
            return false;
        }
    }

    private Optional<String> addressOf(String nodeId) {
        return nodeRepository.findById(nodeId)
                .filter(node -> node.getStatus() != EngineNode.Status.DOWN)
                .map(EngineNode::getAddress);
    }

    private boolean isSelf(String nodeId) {
        return nodeId.equals(nodeRegistry.getSelfNodeId());
    }

    private String nodeOf(UUID hostInstanceId) {
        if (hostInstanceId == null) {
            return null;
        }
        return instanceRepository.findById(hostInstanceId)
                .map(AgentHostInstance::getControlNodeId)
                .orElse(null);
    }

    private Optional<WebSocketSession> resolveLocalSocket(UUID sessionId, UUID hostInstanceId,
                                                          boolean channelFirst) {
        if (channelFirst && sessionId != null) {
            Optional<WebSocketSession> channel = channelRegistry.getChannel(sessionId);
            if (channel.isPresent()) {
                return channel;
            }
        }
        return connectionManager.getSession(hostInstanceId);
    }
}