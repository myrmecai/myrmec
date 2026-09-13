// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
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
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, AgentHost host, UUID instanceId) {}

    private Setup openedHost() throws Exception {
        AgentProfile profile = data.agentProfile().named("lc-profile").create();
        AgentHostCreationResult created =
                data.agent().named("lc-host").withProfile(profile).withMaxAgents(10).create();
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

    @Test
    void normalDisconnectClosesInstanceAndUnregisters() throws Exception {
        Setup setup = openedHost();

        handler.afterConnectionClosed(setup.session(), CloseStatus.NORMAL);

        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
        assertThat(row.getCloseReason()).isEqualTo("DISCONNECT");
        assertThat(row.getClosedAt()).isNotNull();
        assertThat(handler.getConnectionManager().getSession(setup.instanceId())).isEmpty();
    }

    @Test
    void abnormalDisconnectClosesWithAbnormalReason() throws Exception {
        Setup setup = openedHost();

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        assertThat(instances.findById(setup.instanceId()).orElseThrow().getCloseReason())
                .isEqualTo("ABNORMAL_DISCONNECT");
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
        AgentProfile profile = data.agentProfile().named("ns-profile").create();
        AgentHost host = data.agent().named("ns-host").withProfile(profile).create().agent();
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
