// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
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
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
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
 * §7.5 dedicated session channel (A1): session.open carries the channel offer;
 * channel.open consumes the single-use token and answers channel.opened with
 * the session's cursor; expired/wrong-session tokens are rejected; execution
 * frames ride the same inbound arm as the control socket while control-only
 * frames get INVALID_MESSAGE; engine→host sends prefer the channel socket;
 * channel loss never closes the session.
 */
class HostChannelTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired HostChannelWebSocketHandler channelHandler;
    @Autowired ChannelTokenService channelTokenService;
    @Autowired ChannelConnectionRegistry channelRegistry;
    @Autowired SessionAllocator allocator;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired ai.myrmec.engine.inference.SessionContextAssembler sessionContextAssembler;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record ControlSocket(WebSocketSession session, UUID hostId, UUID instanceId) {}

    private ControlSocket openedHost() throws Exception {
        AgentHostCreationResult created =
                data.agent().named("ch-host").withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("ch-sock-" + UUID.randomUUID());
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
        handler.handleMessage(session, new TextMessage(open));

        JsonNode opened = lastReply(session);
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        return new ControlSocket(session, host.getId(), instanceId);
    }

    private List<JsonNode> replies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    private JsonNode lastReply(WebSocketSession session) {
        var r = replies(session);
        return r.get(r.size() - 1);
    }

    /** A channel socket with the handshake attrs the interceptor would set. */
    private WebSocketSession channelSocket(UUID hostId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        lenient().when(socket.getId()).thenReturn("ch-sock-" + UUID.randomUUID());
        lenient().when(socket.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, hostId);
        lenient().when(socket.getAttributes()).thenReturn(attrs);
        return socket;
    }

    private String channelOpenFrame(UUID sessionId, String token, long resumeFrom) {
        return """
                { "protocolVersion": 1, "messageId": "m-chn-%s", "type": "channel.open",
                  "sentAt": "%s", "payload": { "sessionId": "%s",
                  "resumeFromSequence": %d, "token": "%s" } }
                """.formatted(UUID.randomUUID().toString().substring(0, 8),
                Instant.now(), sessionId, resumeFrom, token);
    }

    /** Assemble a real session.open payload (channel enabled) via the assembler. */
    private void acceptAndOpen(ControlSocket host, UUID sessionId) throws Exception {
        String accept = """
                { "protocolVersion": 1, "messageId": "m-acc", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, sessionId, sessionId, Instant.now());
        handler.handleMessage(host.session(), new TextMessage(accept));
        String openedFrame = """
                { "protocolVersion": 1, "messageId": "m-opd", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, sessionId);
        handler.handleMessage(host.session(), new TextMessage(openedFrame));
    }

    @Test
    void sessionOpenCarriesChannelEndpointAndTokenWhenEnabled() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj").create();
        var profile = data.agentProfile().named("ch-prof").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();
        acceptAndOpen(host, sessionId);

        // Enable the channel feature, assemble, capture the session.open frame.
        ReflectionTestUtils.setField(sessionContextAssembler, "channelEnabled", true);
        ReflectionTestUtils.setField(sessionContextAssembler, "channelEndpoint",
                "engine-1.internal:9090");
        try {
            handler.sendSessionOpen(sessionId, profile.getId());
            JsonNode open = lastReply(host.session());
            assertThat(open.path("type").asText()).isEqualTo("session.open");
            JsonNode channel = open.path("payload").path("channel");
            assertThat(channel.isObject()).isTrue();
            assertThat(channel.path("endpoint").asText())
                    .isEqualTo("wss://engine-1.internal:9090/ws/host/channel");
            assertThat(channel.path("token").asText()).isNotBlank();
        } finally {
            ReflectionTestUtils.setField(sessionContextAssembler, "channelEnabled", false);
            ReflectionTestUtils.setField(sessionContextAssembler, "channelEndpoint", "");
        }
    }

    private UUID agentIdOf(UUID instanceId) {
        return instances.findById(instanceId).map(AgentHostInstance::getAgentHostId)
                .orElseThrow();
    }

    @Test
    void channelOpenWithValidTokenBindsAndTokenIsSingleUse() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj2").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();
        acceptAndOpen(host, sessionId);

        String token = channelTokenService.mint(sessionId, host.instanceId());
        WebSocketSession socket = channelSocket(host.hostId());

        channelHandler.handleMessage(socket, new TextMessage(channelOpenFrame(sessionId, token, 0L)));

        JsonNode opened = lastReply(socket);
        assertThat(opened.path("type").asText()).isEqualTo("channel.opened");
        assertThat(opened.path("payload").path("sessionId").asText())
                .isEqualTo(sessionId.toString());
        // The engine's current cursor for the session.
        long cursor = sessionRepository.findById(sessionId).orElseThrow()
                .getHighestContiguousSequence();
        assertThat(opened.path("payload").path("highestContiguousSequence").asLong())
                .isEqualTo(cursor);
        // Registered in the channel registry.
        assertThat(channelRegistry.getChannel(sessionId)).contains(socket);

        // Single-use: a second channel.open with the SAME token is rejected.
        WebSocketSession second = channelSocket(host.hostId());
        channelHandler.handleMessage(second,
                new TextMessage(channelOpenFrame(sessionId, token, 0L)));
        JsonNode error = lastReply(second);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
        // And no binding was replaced.
        assertThat(channelRegistry.getChannel(sessionId)).contains(socket);
    }

    @Test
    void expiredAndWrongSessionTokensAreRejected() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj3").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();

        // Expired: mint, then force expiry via the TTL property.
        String token = channelTokenService.mint(sessionId, host.instanceId());
        int originalTtl = (int) ReflectionTestUtils.getField(channelTokenService, "tokenTtlSeconds");
        ReflectionTestUtils.setField(channelTokenService, "tokenTtlSeconds", 0);
        try {
            String expiredToken = channelTokenService.mint(sessionId, host.instanceId());
            WebSocketSession socket = channelSocket(host.hostId());
            channelHandler.handleMessage(socket,
                    new TextMessage(channelOpenFrame(sessionId, expiredToken, 0L)));
            JsonNode error = lastReply(socket);
            assertThat(error.path("payload").path("code").asText())
                    .isEqualTo(HostProtocol.INVALID_MESSAGE);
            assertThat(channelRegistry.getChannel(sessionId)).isEmpty();
        } finally {
            ReflectionTestUtils.setField(channelTokenService, "tokenTtlSeconds", originalTtl);
        }
        // The not-yet-expired token minted above is still consumable below
        // (it bound to the WRONG session here → IDENTITY_MISMATCH).
        WebSocketSession wrong = channelSocket(host.hostId());
        UUID otherSession = UUID.randomUUID();
        channelHandler.handleMessage(wrong,
                new TextMessage(channelOpenFrame(otherSession, token, 0L)));
        JsonNode error = lastReply(wrong);
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        assertThat(channelRegistry.getChannel(sessionId)).isEmpty();
    }

    @Test
    void tokenFromAnotherHostIsRejected() throws Exception {
        ControlSocket hostA = openedHost();
        var project = data.project().named("ch-proj4").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(hostA.instanceId())).orElseThrow();

        ControlSocket hostB = openedHost(); // a different durable host
        String token = channelTokenService.mint(sessionId, hostA.instanceId());

        WebSocketSession socketB = channelSocket(hostB.hostId());
        channelHandler.handleMessage(socketB,
                new TextMessage(channelOpenFrame(sessionId, token, 0L)));
        JsonNode error = lastReply(socketB);
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);
        assertThat(channelRegistry.getChannel(sessionId)).isEmpty();
    }

    @Test
    void executionDeltaOnChannelRidesSameInboundArmAndSessionOpenIsRefused() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj5").create();
        var profile = data.agentProfile().named("ch-prof5").create();
        var conversation = data.conversation().inProject(project).create();

        UUID sessionId = allocator.offer("CONVERSATION", conversation.getId(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();
        acceptAndOpen(host, sessionId);

        // A RUNNING conversation execution the delta references.
        SessionExecution execution = new SessionExecution();
        execution.setSessionId(sessionId);
        execution.setServiceType("CONVERSATION");
        execution.setRequestId(conversation.getId().toString());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution = executionRepository.save(execution);

        String token = channelTokenService.mint(sessionId, host.instanceId());
        WebSocketSession socket = channelSocket(host.hostId());
        channelHandler.handleMessage(socket,
                new TextMessage(channelOpenFrame(sessionId, token, 0L)));
        assertThat(lastReply(socket).path("type").asText()).isEqualTo("channel.opened");

        // execution.delta on the CHANNEL socket: bridges to the stream broker
        // exactly like the control-socket arm.
        List<String> deltas = new java.util.ArrayList<>();
        var broker = streamBroker;
        broker.subscribe(conversation.getId(), new ConversationSubscriber() {
            @Override public String id() { return "ch-test"; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) throws IOException { deltas.add(jsonFrame); }
        });
        String delta = """
                { "protocolVersion": 1, "messageId": "m-ch-delta", "type": "execution.delta",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "index": 0, "content": "via channel",
                  "contentType": "text" } }
                """.formatted(Instant.now(), host.instanceId(), sessionId, execution.getId(),
                execution.getId());
        channelHandler.handleMessage(socket, new TextMessage(delta));
        assertThat(deltas).hasSize(1);
        assertThat(mapper.readTree(deltas.get(0)).path("payload").path("content").asText())
                .isEqualTo("via channel");

        // Control-only frame on the channel socket → INVALID_MESSAGE.
        String sessionOpenFrame = """
                { "protocolVersion": 1, "messageId": "m-ch-bad", "type": "session.open",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "payload": {} }
                """.formatted(Instant.now(), host.instanceId(), sessionId);
        channelHandler.handleMessage(socket, new TextMessage(sessionOpenFrame));
        JsonNode refused = lastReply(socket);
        assertThat(refused.path("type").asText()).isEqualTo("protocol.error");
        assertThat(refused.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
    }

    private org.springframework.context.ApplicationContext streamBrokerUnused() {
        return null;
    }

    @Autowired
    private ConversationStreamBroker streamBroker;

    @Test
    void engineToHostSendPrefersChannelSocket() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj6").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();
        acceptAndOpen(host, sessionId);

        // Bind a channel.
        String token = channelTokenService.mint(sessionId, host.instanceId());
        WebSocketSession socket = channelSocket(host.hostId());
        channelHandler.handleMessage(socket,
                new TextMessage(channelOpenFrame(sessionId, token, 0L)));
        assertThat(lastReply(socket).path("type").asText()).isEqualTo("channel.opened");

        // engine→host send: session.close resolves the CHANNEL socket first.
        handler.sendSessionClose(sessionId, "CONVERSATION_ARCHIVED");

        var controlSends = replies(host.session()).stream()
                .filter(n -> "session.close".equals(n.path("type").asText())).toList();
        assertThat(controlSends).isEmpty();
        JsonNode onChannel = lastReply(socket);
        assertThat(onChannel.path("type").asText()).isEqualTo("session.close");
        assertThat(onChannel.path("payload").path("sessionId").asText())
                .isEqualTo(sessionId.toString());
    }

    @Test
    void channelCloseDropsBindingButSessionStaysActive() throws Exception {
        ControlSocket host = openedHost();
        var project = data.project().named("ch-proj7").create();
        UUID sessionId = allocator.offer("CONVERSATION", UUID.randomUUID(), "CONVERSATION",
                project.getId(), agentIdOf(host.instanceId())).orElseThrow();
        acceptAndOpen(host, sessionId);

        String token = channelTokenService.mint(sessionId, host.instanceId());
        WebSocketSession socket = channelSocket(host.hostId());
        channelHandler.handleMessage(socket,
                new TextMessage(channelOpenFrame(sessionId, token, 0L)));
        assertThat(channelRegistry.getChannel(sessionId)).isPresent();

        // The channel socket dies.
        channelHandler.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);

        // Binding gone; the session is untouched (still ACTIVE).
        assertThat(channelRegistry.getChannel(sessionId)).isEmpty();
        Session row = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(row.getAllocationState())
                .isEqualTo(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);
        // The control socket still serves the session — engine→host works.
        handler.sendSessionClose(sessionId, "CONVERSATION_ARCHIVED");
        var controlSends = replies(host.session()).stream()
                .filter(n -> "session.close".equals(n.path("type").asText())).toList();
        assertThat(controlSends).hasSize(1);
    }
}