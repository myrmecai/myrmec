// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * §12.2 heartbeat-staleness sweep: an OPEN instance silent past
 * {@code factor × heartbeat-interval-seconds} is a zombie — the sweeper
 * force-closes its control socket (which routes into the existing recovery
 * entry) and arms the RECOVERING window idempotently. Fresh (never-beaten,
 * recently opened) instances and recently-beating instances are untouched;
 * a disabled configuration is a no-op.
 */
class HostHeartbeatStalenessSweeperTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired HostConnectionManager connectionManager;
    @Autowired SessionAllocator allocator;
    @Autowired TestDataBuilder data;
    @Autowired HostHeartbeatStalenessSweeper sweeper;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, AgentHost host, UUID instanceId) {}

    private Setup openedHost(String name) throws Exception {
        AgentHostCreationResult created =
                data.agent().named(name).withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("hbs-sock-" + UUID.randomUUID());
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
        org.mockito.Mockito.verify(session).sendMessage(captor.capture());
        JsonNode opened = mapper.readTree(captor.getValue().getPayload());
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        // host.open already registered this socket in the connection manager —
        // the registry entry the sweeper resolves through.
        assertThat(connectionManager.getSession(instanceId)).contains(session);
        return new Setup(session, host, instanceId);
    }

    /** Age the instance's last heartbeat to well past the default 30s window. */
    private void ageHeartbeat(UUID instanceId, long secondsAgo) {
        AgentHostInstance instance = instances.findById(instanceId).orElseThrow();
        ReflectionTestUtils.setField(instance, "lastHeartbeatAt",
                Instant.now().minusSeconds(secondsAgo));
        instances.saveAndFlush(instance);
    }

    @Test
    void staleOpenInstanceSocketIsClosedAndInstanceEntersRecovery() throws Exception {
        Setup setup = openedHost("hbs-stale");
        ageHeartbeat(setup.instanceId(), 120);

        int acted = sweeper.sweep(Instant.now());

        assertThat(acted).isEqualTo(1);
        // The zombie socket got the GOING_AWAY close.
        org.mockito.Mockito.verify(setup.session()).close(CloseStatus.GOING_AWAY);
        // And the row is parked in the bounded recovery window (the close path
        // and the sweeper's direct arm converge — startRecovery is idempotent).
        AgentHostInstance after = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AgentHostInstance.Status.RECOVERING);
        assertThat(after.getRecoveryExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void freshInstanceWithoutHeartbeatIsSparedByOpenedAtGrace() throws Exception {
        Setup setup = openedHost("hbs-fresh");

        int acted = sweeper.sweep(Instant.now());

        // The instance never signalled liveness, but it was opened moments ago —
        // the openedAt grace covers the first window.
        assertThat(acted).isZero();
        assertThat(instances.findById(setup.instanceId()).orElseThrow().getStatus())
                .isEqualTo(AgentHostInstance.Status.OPEN);
        org.mockito.Mockito.verify(setup.session(), org.mockito.Mockito.never())
                .close(org.mockito.Mockito.any(CloseStatus.class));
    }

    @Test
    void recentlyBeatingInstanceIsUntouched() throws Exception {
        Setup setup = openedHost("hbs-beating");
        AgentHostInstance instance = instances.findById(setup.instanceId()).orElseThrow();
        instance.markHeartbeat();
        instances.saveAndFlush(instance);

        int acted = sweeper.sweep(Instant.now());

        assertThat(acted).isZero();
        assertThat(instances.findById(setup.instanceId()).orElseThrow().getStatus())
                .isEqualTo(AgentHostInstance.Status.OPEN);
        org.mockito.Mockito.verify(setup.session(), org.mockito.Mockito.never())
                .close(org.mockito.Mockito.any(CloseStatus.class));
    }

    @Test
    void disabledConfigurationIsANoOp() throws Exception {
        Setup setup = openedHost("hbs-disabled");
        ageHeartbeat(setup.instanceId(), 120);

        HostHeartbeatStalenessSweeper disabled = new HostHeartbeatStalenessSweeper(
                instances, connectionManager, allocator, false, 2, 15);
        disabled.scheduled();

        AgentHostInstance after = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AgentHostInstance.Status.OPEN);
        org.mockito.Mockito.verify(setup.session(), org.mockito.Mockito.never())
                .close(org.mockito.Mockito.any(CloseStatus.class));
    }

    @Test
    void staleInstanceWithoutRegisteredSocketStillEntersRecovery() throws Exception {
        Setup setup = openedHost("hbs-nosock");
        ageHeartbeat(setup.instanceId(), 120);
        // The socket vanished from the registry without the close handler
        // running (crash window) — OPEN row, no live socket.
        connectionManager.unregister(setup.session());

        int acted = sweeper.sweep(Instant.now());

        assertThat(acted).isEqualTo(1);
        AgentHostInstance after = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AgentHostInstance.Status.RECOVERING);
        assertThat(after.getRecoveryExpiresAt()).isAfter(Instant.now());
    }
}