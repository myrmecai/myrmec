// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.inference.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * §6.4/§6.5/§3.2: heartbeat refreshes liveness; capacity changes clamp and
 * store; disconnect closes the instance row with a standardized reason and
 * unregisters the socket. Unknown types get protocol.error
 * UNSUPPORTED_MESSAGE with the offending messageId correlated; frames whose
 * hostInstanceId mismatches the connection get IDENTITY_MISMATCH.
 */
class HostControlLifecycleTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired ai.myrmec.engine.inference.SessionRepository sessionRepository;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, AgentHost host, UUID instanceId) {}

    private Setup openedHost() throws Exception {
        AgentHostCreationResult created =
                data.agent().named("lc-host").withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("lc-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode opened = mapper.readTree(captor.getValue().getPayload());
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        return new Setup(session, host, instanceId);
    }

    private List<JsonNode> allReplies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    @Test
    void heartbeatRefreshesLiveness() throws Exception {
        Setup setup = openedHost();
        String heartbeat = """
                { "protocolVersion": 1, "messageId": "m-hb", "type": "host.heartbeat",
                  "sentAt": "%s", "hostInstanceId": "%s", "payload": {
                    "observedAt": "%s", "effectivePoolSize": 2, "activeSessionCount": 0,
                    "pendingOfferCount": 0, "health": "HEALTHY" } }
                """.formatted(Instant.now(), setup.instanceId(), Instant.now());

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(heartbeat));

        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getLastHeartbeatAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(AgentHostInstance.Status.OPEN);
    }

    @Test
    void capacityChangeClampsAndStores() throws Exception {
        Setup setup = openedHost();
        // maxAgents is 10 — announce 15, expect the clamp.
        String capacity = """
                { "protocolVersion": 1, "messageId": "m-cap", "type": "host.capacity",
                  "sentAt": "%s", "hostInstanceId": "%s", "payload": {
                    "poolSize": 15, "reason": "LOCAL_RESOURCE_CHANGE",
                    "reportedCapacity": { "cpuCount": 8 } } }
                """.formatted(Instant.now(), setup.instanceId());

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(capacity));

        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getPoolSize()).isEqualTo(setup.host().getMaxAgents());
    }

    /**
     * §13 (A2): a disconnect parks the instance in the bounded RECOVERING
     * window instead of closing it. The socket is unregistered; the drop
     * reason is recorded on the row for forensics only when the window
     * later lapses (the retention sweep stamps HOST_LOST at expiry).
     */
    @Test
    void normalDisconnectParksInstanceRecoveringAndUnregisters() throws Exception {
        Setup setup = openedHost();

        handler.afterConnectionClosed(setup.session(), CloseStatus.NORMAL);

        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AgentHostInstance.Status.RECOVERING);
        assertThat(row.getClosedAt()).isNull();
        assertThat(row.getRecoveryExpiresAt()).isNotNull();
        assertThat(handler.getConnectionManager().getSession(setup.instanceId())).isEmpty();
    }

    @Test
    void abnormalDisconnectAlsoParksRecovering() throws Exception {
        Setup setup = openedHost();

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        assertThat(instances.findById(setup.instanceId()).orElseThrow().getStatus())
                .isEqualTo(AgentHostInstance.Status.RECOVERING);
    }

    /**
     * §13 (A2): the disconnect path parks the instance RECOVERING — it must
     * NOT touch the instance's sessions (they stay parked pending
     * host.resume; the retention sweep's {@code expireRecoveredInstances}
     * is what closes them HOST_LOST if the window lapses).
     */
    @Test
    void hostLostClosesOpenSessionsOnTheDeadInstance() throws Exception {
        Setup setup = openedHost();

        // Swap in a verifying mock, run, then RESTORE the production bean —
        // the handler is a singleton across the suite and a leaked mock
        // breaks every later test's session.accept.
        Object original = org.springframework.test.util.ReflectionTestUtils
                .getField(handler, "sessionAllocator");
        try {
            ai.myrmec.engine.inference.SessionAllocator allocator =
                    org.mockito.Mockito.mock(ai.myrmec.engine.inference.SessionAllocator.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    handler, "sessionAllocator", allocator);

            handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

            // §13: the close-path must NOT close sessions — the drop parks
            // the instance RECOVERING and leaves the sessions untouched.
            org.mockito.Mockito.verify(allocator, org.mockito.Mockito.never())
                    .closeAllOnHostInstance(setup.instanceId(), "HOST_LOST");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    handler, "sessionAllocator", original);
        }
    }

    /**
     * §9 (P6-T6): the allocator-level discriminator — a real ACTIVE session
     * on a dead instance closes with close_reason=HOST_LOST via
     * {@code closeAllOnHostInstance}, and the sweep's
     * {@code expireHostLostSessions} reconciles instances that vanished
     * entirely (crash before the socket close handler ran).
     */
    @Test
    void closeAllOnHostInstanceAndSweepReconcileHostLostSessions() {
        var project = data.project().named("host-lost-recon").create();
        AgentHostCreationResult created =
                data.agent().named("host-lost-host").withMaxAgents(5).create();
        AgentHost host = created.agent();

        ai.myrmec.engine.inference.SessionAllocator allocator = hostLostSweepTarget();

        // A live OPEN instance carrying an ACTIVE session.
        AgentHostInstance live = instances.save(AgentHostInstance.open(
                host, null, "live-host", 2, Map.of(), "node-1"));
        Session activeOnLive = new Session();
        activeOnLive.setServiceType("CONVERSATION");
        activeOnLive.setRefId(UUID.randomUUID());
        activeOnLive.setProjectId(project.getId());
        activeOnLive.setKind("CONVERSATION");
        activeOnLive.setHostInstanceId(live.getId());
        activeOnLive.setAllocationState(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);
        sessionRepository.save(activeOnLive);

        // A session whose instance CLOSED between the socket-died close and
        // the sweep (crash before the close handler ran is the same shape:
        // no OPEN instance row carries the session).
        AgentHostInstance dead = AgentHostInstance.open(
                host, null, "dead-host", 2, Map.of(), "node-2");
        dead.close("ABNORMAL_DISCONNECT");
        instances.save(dead);
        Session orphan = new Session();
        orphan.setServiceType("CONVERSATION");
        orphan.setRefId(UUID.randomUUID());
        orphan.setProjectId(project.getId());
        orphan.setKind("CONVERSATION");
        orphan.setHostInstanceId(dead.getId());
        orphan.setAllocationState(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);
        sessionRepository.save(orphan);

        // 1. Socket-died path: closeAllOnHostInstance closes only the live
        //    instance's session, stamps HOST_LOST, and skips the orphan.
        int closed = allocator.closeAllOnHostInstance(live.getId(), "HOST_LOST");
        assertThat(closed).isEqualTo(1);
        entityManager.clear();
        Session closedRow = sessionRepository.findById(activeOnLive.getId()).orElseThrow();
        assertThat(closedRow.getAllocationState())
                .isEqualTo(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(closedRow.getCloseReason()).isEqualTo("HOST_LOST");
        assertThat(sessionRepository.findById(orphan.getId()).orElseThrow()
                .getAllocationState())
                .isEqualTo(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);

        // 2. Sweep path: the orphan (dead instance) reconciles too.
        int swept = allocator.expireHostLostSessions();
        assertThat(swept).isGreaterThanOrEqualTo(1);
        entityManager.clear();
        Session sweptOrphan = sessionRepository.findById(orphan.getId()).orElseThrow();
        assertThat(sweptOrphan.getAllocationState())
                .isEqualTo(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(sweptOrphan.getCloseReason()).isEqualTo("HOST_LOST");
    }

    /** Resolve the production allocator bean (the real subject under test). */
    private ai.myrmec.engine.inference.SessionAllocator hostLostSweepTarget() {
        return applicationContext.getBean(ai.myrmec.engine.inference.SessionAllocator.class);
    }

    @Test
    void unknownTypeGetsUnsupportedMessageError() throws Exception {
        Setup setup = openedHost();
        String frame = """
                { "protocolVersion": 1, "messageId": "m-x", "type": "nonsense.frame",
                  "sentAt": "%s", "hostInstanceId": "%s", "payload": {} }
                """.formatted(Instant.now(), setup.instanceId());

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(frame));

        JsonNode error = allReplies(setup.session()).get(allReplies(setup.session()).size() - 1);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("correlationId").asText()).isEqualTo("m-x");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.UNSUPPORTED_MESSAGE);
    }

    @Test
    void mismatchedHostInstanceIdIsIdentityMismatch() throws Exception {
        Setup setup = openedHost();
        String frame = """
                { "protocolVersion": 1, "messageId": "m-id", "type": "host.heartbeat",
                  "sentAt": "%s", "hostInstanceId": "%s", "payload": {} }
                """.formatted(Instant.now(), UUID.randomUUID());

        ((WebSocketHandler) handler).handleMessage(setup.session(), new TextMessage(frame));

        JsonNode error = allReplies(setup.session()).get(allReplies(setup.session()).size() - 1);
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        assertThat(error.path("correlationId").asText()).isEqualTo("m-id");
    }

    @Test
    void lifecycleFrameBeforeOpenIsInvalidState() throws Exception {
        AgentHost host = data.agent().named("ns-host").create().agent();
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("ns-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String frame = """
                { "protocolVersion": 1, "messageId": "m-ns", "type": "host.heartbeat",
                  "sentAt": "%s", "payload": {} }
                """.formatted(Instant.now());
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(frame));

        JsonNode error = allReplies(session).get(0);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.INVALID_STATE);
    }
}
