// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * End-to-end allocation flow over the host socket (§7): engine-initiated
 * session.offer -> host session.accept -> engine session.open (full
 * assembled context) -> host session.opened (worker minted, ACTIVE) ->
 * engine session.close -> host session.closed. Driven through the real
 * handler with the house stub-session pattern.
 */
class SessionAllocationFlowTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired SessionAllocator allocator;
    @Autowired SessionRepository sessionRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private record Host(AgentHost host, AgentProfile profile, WebSocketSession session) {}

    private Host openedHost() throws Exception {
        AgentProfile profile = data.agentProfile().named("f-profile").create();
        AgentHostCreationResult created =
                data.agent().named("f-host").withProfile(profile).withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("f-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 4,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));
        return new Host(host, profile, session);
    }

    private List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private JsonNode lastReply(WebSocketSession session) {
        return replies(session).get(replies(session).size() - 1);
    }

    @Test
    void fullOfferAcceptOpenOpenedCloseFlowOverTheSocket() throws Exception {
        Host hostSetup = openedHost();
        UUID instanceId = (UUID) hostSetup.session().getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);
        var project = data.project().named("f-proj").create();

        // ---- Engine reserves + offers ----
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), hostSetup.host().getId()).orElseThrow();

        // ---- Host accepts ----
        String accept = """
                { "protocolVersion": 1, "messageId": "m-acc", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(Instant.now(), instanceId, sessionId,
                sessionId, sessionId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(accept));
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_INITIALIZING);

        // ---- Host opened: worker minted, ACTIVE, lease running ----
        String openedFrame = """
                { "protocolVersion": 1, "messageId": "m-opd", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(openedFrame));
        Session row = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(row.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
        assertThat(row.getIdleLeaseExpiresAt()).isAfter(Instant.now());

        // ---- Engine closes (archive) ----
        String closeFrame = """
                { "protocolVersion": 1, "messageId": "m-close", "type": "session.close",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "reasonCode": "CONVERSATION_ARCHIVED", "gracePeriodSeconds": 5 } }
                """.formatted(Instant.now(), instanceId, sessionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(closeFrame));
        // session.close is engine->host; the host answers session.closed.
        String closedFrame = """
                { "protocolVersion": 1, "messageId": "m-cld", "type": "session.closed",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "closedAt": "%s", "reasonCode": "CONVERSATION_ARCHIVED" } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId, Instant.now());
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(closedFrame));

        Session closed = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(closed.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        // Capacity returned: a second offer succeeds on the same instance.
        assertThat(allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), hostSetup.host().getId())).isPresent();
    }

    @Test
    void sessionOpenCarriesAssembledContext() throws Exception {
        Host hostSetup = openedHost();
        UUID instanceId = (UUID) hostSetup.session().getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);
        var project = data.project().named("f-proj2").create();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), hostSetup.host().getId()).orElseThrow();
        allocator.accept(sessionId);

        // Engine-initiated session.open: captured from the wire.
        handler.sendSessionOpen(sessionId, hostSetup.profile().getId());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        org.mockito.Mockito.verify(hostSetup.session(),
                org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        List<JsonNode> opens = captor.getAllValues().stream()
                .map(TextMessage::getPayload)
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .filter(n -> "session.open".equals(n.path("type").asText()))
                .toList();
        assertThat(opens).hasSize(1);
        JsonNode payload = opens.get(0).path("payload");
        assertThat(payload.path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(payload.path("profileVersionId")).isNotNull();
        assertThat(payload.path("model")).isNotNull();
        assertThat(payload.path("tools").isArray()).isTrue();
    }

    @Test
    void rejectReleasesAndErrorsStayDisciplined() throws Exception {
        Host hostSetup = openedHost();
        UUID instanceId = (UUID) hostSetup.session().getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID);
        var project = data.project().named("f-proj3").create();

        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), hostSetup.host().getId()).orElseThrow();
        String reject = """
                { "protocolVersion": 1, "messageId": "m-rej", "type": "session.reject",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s",
                    "reasonCode": "NO_CAPACITY", "message": "no slot", "retryable": true } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId, sessionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(reject));

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);

        // An out-of-order opened (no accept) is INVALID_STATE, socket stays open.
        String bogusOpened = """
                { "protocolVersion": 1, "messageId": "m-bogus", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), instanceId, sessionId, sessionId);
        ((WebSocketHandler) handler).handleMessage(hostSetup.session(), new TextMessage(bogusOpened));
        JsonNode error = lastReply(hostSetup.session());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("INVALID_STATE");
    }
}
