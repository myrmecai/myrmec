// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.ChannelConnectionRegistry;
import ai.myrmec.engine.websocket.host.HostConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §20 inter-node host-socket routing (A3, decision H5):
 * <ol>
 *   <li>a local instance (control_node_id == self) is served by direct socket
 *       send with NO relay HTTP;</li>
 *   <li>a remote instance's frame is relayed over HTTP as
 *       {@code {targetInstanceId, frame}}, and the receiving controller
 *       delivers it to the right local socket;</li>
 *   <li>relay failure falls back to a local socket when one exists, and
 *       fails the send when not;</li>
 *   <li>{@code host.open} stamps {@code control_node_id} — the same source
 *       the wire's {@code serverNodeId} reports.</li>
 * </ol>
 * The relay gate is switched on for the whole class so the REAL controller
 * (MockMvc test (e)) can accept the POST; the service-level tests stub
 * {@code postRelay} so no live HTTP is needed.
 */
@TestPropertySource(properties = {
        "myrmec.node.relay.enabled=true",
        "myrmec.node.relay.secret=mesh-secret-must-match"
})
class HostFrameRelayTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired NodeRegistryService nodeRegistry;
    @Autowired HostConnectionManager connectionManager;
    @Autowired ChannelConnectionRegistry channelRegistry;
    @Autowired EngineNodeRepository nodeRepository;
    @Autowired WebApplicationContext context;

    private HostFrameRelayService relay;
    private final AtomicInteger relayPosts = new AtomicInteger();
    private final AtomicInteger deliveredPosts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        // Enabled relay + a controlled postRelay seam: counts attempts and can
        // be flipped to fail via failRelay.
        relay = new HostFrameRelayService(
                instances, nodeRepository, nodeRegistry, connectionManager, channelRegistry,
                true, "test-mesh-secret") {
            @Override
            protected boolean postRelay(String peerAddress, UUID targetInstanceId, String frameJson) {
                relayPosts.incrementAndGet();
                if (failRelay) {
                    return false;
                }
                // Simulated peer replica: the frame arrives at the peer's
                // controller, which resolves ITS local socket registry.
                deliveredPosts.incrementAndGet();
                simulatedPeerSockets.put(targetInstanceId, frameJson);
                return true;
            }
        };
        relayPosts.set(0);
        deliveredPosts.set(0);
        failRelay = false;
        simulatedPeerSockets.clear();
        // The self node must be in the registry with an address for the
        // "isSelf" fast path to be reachable via the DB-backed lookup.
        selfNodeId = nodeRegistry.getSelfNodeId();
    }

    private boolean failRelay;
    private final Map<UUID, String> simulatedPeerSockets = new java.util.concurrent.ConcurrentHashMap<>();
    private String selfNodeId;

    private AgentHostInstance instanceOn(String nodeId) {
        AgentHostCreationResult created = data.agent().named("relay-host-" + UUID.randomUUID()).create();
        return instances.saveAndFlush(AgentHostInstance.open(
                created.agent(), null, UUID.randomUUID().toString(), "laptop",
                4, Map.of(), nodeId));
    }

    private WebSocketSession stubSocket() {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("relay-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        try {
            doAnswer(inv -> inv.getArgument(0) instanceof TextMessage tm ? tm.getPayload() : null)
                    .when(session).sendMessage(any(TextMessage.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return session;
    }

    private String addressOf(String nodeId) {
        EngineNode node = nodeRepository.findById(nodeId).orElse(null);
        if (node != null) {
            return node.getAddress();
        }
        return null;
    }

    /** Register a UP peer replica the routing table can resolve an address from. */
    private EngineNode peerNode(String nodeId) {
        EngineNode node = new EngineNode();
        node.setNodeId(nodeId);
        node.setAddress("peer-" + nodeId + ":9090");
        node.setStatus(EngineNode.Status.UP);
        node.setStartedAt(java.time.Instant.now());
        node.setLastHeartbeatAt(java.time.Instant.now());
        return node;
    }

    // ── (a) local instance: direct send, no relay HTTP ──────────────────

    @Test
    void localInstanceSendsDirectlyWithoutRelayHttp() {
        AgentHostInstance instance = instanceOn(nodeRegistry.getSelfNodeId());
        connectionManager.register(instance.getId(), stubSocket());

        boolean sent = relay.send(null, instance.getId(), "{\"type\":\"session.open\"}");

        assertThat(sent).isTrue();
        assertThat(relayPosts.get()).as("no relay HTTP for a self-owned instance").isZero();
    }

    // ── (b) remote instance: relay carries {targetInstanceId, frame} ───

    @Test
    void remoteInstanceRelaysFrameToPeer() {
        String peerNode = "relay-peer-node-" + UUID.randomUUID();
        nodeRepository.save(peerNode(peerNode));
        AgentHostInstance remote = instanceOn(peerNode);

        String frame = "{\"type\":\"execution.start\",\"payload\":{}}";
        boolean sent = relay.send(null, remote.getId(), frame);

        assertThat(sent).isTrue();
        assertThat(relayPosts.get()).isEqualTo(1);
        assertThat(deliveredPosts.get()).isEqualTo(1);
        // The receiving replica got the opaque frame bound to the right instance.
        assertThat(simulatedPeerSockets.get(remote.getId())).isEqualTo(frame);
    }

    // ── (c) relay failure → local fallback; else failure surfaces ─────

    @Test
    void relayFailureFallsBackToLocalSocketWhenOneExists() {
        String peerNode = "relay-peer-node-" + UUID.randomUUID();
        nodeRepository.save(peerNode(peerNode));
        AgentHostInstance remote = instanceOn(peerNode);
        connectionManager.register(remote.getId(), stubSocket());

        failRelay = true;
        boolean sent = relay.send(null, remote.getId(), "{\"type\":\"session.open\"}");

        assertThat(sent).as("local fallback must deliver when this replica holds the socket").isTrue();
        assertThat(relayPosts.get()).isEqualTo(1);
        assertThat(deliveredPosts.get()).isZero();
    }

    @Test
    void relayFailureSurfacesWhenNoLocalSocketExists() {
        String peerNode = "relay-peer-node-" + UUID.randomUUID();
        nodeRepository.save(peerNode(peerNode));
        AgentHostInstance remote = instanceOn(peerNode);
        // No local socket registered anywhere.

        failRelay = true;
        boolean sent = relay.send(null, remote.getId(), "{\"type\":\"session.open\"}");

        assertThat(sent).isFalse();
        assertThat(relayPosts.get()).isEqualTo(1);
    }

    // ── (d) host.open stamps the routing node ──────────────────────────

    @Test
    void hostOpenStampsRoutingNodeFromTheWireSource() throws Exception {
        ai.myrmec.engine.websocket.host.HostControlWebSocketHandler handler =
                context.getBean(ai.myrmec.engine.websocket.host.HostControlWebSocketHandler.class);
        AgentHost host = data.agent().named("relay-open-host").create().agent();

        WebSocketSession session = stubSocket();
        Map<String, Object> attrs = session.getAttributes();
        attrs.put(ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-relay", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(java.time.Instant.now(), UUID.randomUUID());
        handler.handleMessage(session, new TextMessage(open));

        UUID instanceId = (UUID) session.getAttributes()
                .get(ai.myrmec.engine.websocket.host.HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);
        AgentHostInstance row = instances.findById(instanceId).orElseThrow();
        // The routing stamp is the SAME source that produced host.opened.serverNodeId.
        assertThat(row.getControlNodeId()).isEqualTo(nodeRegistry.getSelfNodeId());
    }

    // ── receiving side: the real controller over MockMvc ──────────────

    @Test
    void relayControllerDeliversToTheRightLocalSocket() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        AgentHostInstance local = instanceOn(nodeRegistry.getSelfNodeId());
        WebSocketSession socket = stubSocket();
        connectionManager.register(local.getId(), socket);

        String frame = "{\"type\":\"execution.cancel\",\"payload\":{}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/internal/agent-relay")
                        .header(AgentRelayController.NODE_SECRET_HEADER, "mesh-secret-must-match")
                        .contentType("application/json")
                        .content("""
                                {"targetInstanceId": "%s", "frame": %s}
                                """.formatted(local.getId(),
                                com.fasterxml.jackson.databind.node.TextNode.valueOf(frame).toString())))
                .andExpect(status().isOk());

        // The stub captured the payload the controller pushed to the socket.
        org.mockito.Mockito.verify(socket).sendMessage(
                org.mockito.ArgumentMatchers.argThat((TextMessage m) ->
                        m.getPayload().contains("execution.cancel")));
    }
}