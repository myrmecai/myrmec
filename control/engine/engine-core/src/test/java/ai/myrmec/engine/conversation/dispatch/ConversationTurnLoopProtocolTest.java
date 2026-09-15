// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationParticipant;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * End-to-end conversation turn loop over the unified host-control socket
 * (protocol &sect;7/&sect;8), with no real agent process:
 *
 * <pre>
 * REST POST → ConversationTurnDispatcher
 *   → engine session.offer      (&sect;7.1)
 *   → host   session.accept     (&sect;7.2)
 *   → engine session.open       (&sect;7.3, assembled context)
 *   → host   session.opened     (&sect;7.4, session ACTIVE)
 *   → engine execution.start    (&sect;8.1, the assembled turn)
 *   → host   execution.accept / execution.delta ×2 / execution.complete
 *   → engine bridges message.delta/message.complete to SSE viewers (&sect;8.3/&sect;8.5)
 *   → ASSISTANT transcript row persisted
 * </pre>
 *
 * <p><b>The legacy wire is gone from this path.</b> No {@code agent.bind},
 * {@code conversation.attach}, or {@code inference.assign} frame is involved at
 * any point: the turn rides the same host-control socket that carried
 * {@code host.open}, and the assertions below pin that (every captured frame is
 * checked to carry none of the legacy types).</p>
 *
 * <p>Socket shape mirrors {@code SessionAllocationFlowTest}/{@code
 * ExecutionLifecycleFlowTest}: a real {@code host.open} on a mock
 * {@link WebSocketSession} creates the live {@code AgentHostInstance}, and a
 * broker subscriber stands in for the UI viewer.</p>
 */
class ConversationTurnLoopProtocolTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationStreamBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HostControlWebSocketHandler hostHandler;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionExecutionRepository executionRepository;

    @Autowired
    private ai.myrmec.engine.assistant.AssistantService assistantService;

    @Autowired
    private ai.myrmec.engine.assistant.AssistantVersionService assistantVersionService;

    @Autowired
    private ai.myrmec.engine.conversation.ConversationRepository conversationRepository;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Legacy wire types this test asserts are never used on the unified path. */
    private static final List<String> LEGACY_TYPES =
            List.of("agent.bind", "agent.bind.ack", "agent.bind.nack",
                    "conversation.attach", "inference.assign");

    private record Host(AgentHost host, AgentProfile profile, WebSocketSession session,
                        UUID instanceId, BlockingQueue<String> outbound) {}

    @Test
    void userMessagePostDispatchesTurnAndAgentReplyFansOutToViewer() throws Exception {
        // ---------- Arrange: a live host on the control socket ----------
        Host hostSetup = openedHost();
        Project project = data.project().named("turn-loop").create();

        // Conversation pinned to an assistant version (§3.7): the dispatcher and
        // the input assembler resolve the profile from the pin, never the host.
        ai.myrmec.engine.assistant.Assistant pinAssistant = assistantService.createAssistant(
                project.getId(), "Loop Pin Assistant", "desc",
                hostSetup.profile().getId(), TEST_ADMIN_ID);
        ai.myrmec.engine.assistant.AssistantVersion pinVersion =
                assistantVersionService.publish(pinAssistant.getId(), TEST_ADMIN_ID);
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "loop conv", hostSetup.host().getId(), null);
        conv.setAssistantId(pinAssistant.getId());
        conv.setAssistantVersionId(pinVersion.getId());
        conv.setAgentProfileVersionId(pinVersion.getAgentProfileVersionId());
        conversationRepository.save(conv);
        conversationService.addParticipant(
                conv.getId(), TEST_ADMIN_ID, ConversationParticipant.Role.OWNER);

        // Viewer session subscribed to the broker (the UI SSE subscriber).
        BlockingQueue<String> viewerInbound = new LinkedBlockingQueue<>();
        ConversationSubscriber viewerSubscriber = new ConversationSubscriber() {
            private final String id = "viewer-stub-" + UUID.randomUUID();
            @Override public String id() { return id; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) { viewerInbound.add(jsonFrame); }
        };
        broker.subscribe(conv.getId(), viewerSubscriber);

        // ---------- Act 1 — POST the USER message through REST ----------
        ResponseEntity<String> postResponse = restTemplate.exchange(
                "/api/v1/conversations/" + conv.getId() + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("ping from user"), adminHeaders()),
                String.class);
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // ---------- Assert 1 — engine offered a session on the control socket ----------
        JsonNode offer = awaitFrame(hostSetup, "session.offer");
        // §7.1: the offer carries its sessionId in the payload (the committed
        // SessionOfferPayload shape); the envelope stamp starts at accept.
        UUID sessionId = UUID.fromString(offer.path("payload").path("sessionId").asText());
        assertThat(offer.path("payload").path("kind").asText()).isEqualTo("CONVERSATION");
        assertThat(offer.path("payload").path("allocationId").asText())
                .isEqualTo(sessionId.toString());
        assertThat(offer.path("payload").path("ref").path("type").asText())
                .isEqualTo("CONVERSATION");
        assertThat(offer.path("payload").path("ref").path("id").asText())
                .isEqualTo(conv.getId().toString());
        // §7.4: the turn is parked, not started — no execution may precede
        // session.opened.
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_OFFERED);
        assertThat(executionRepository.findBySessionId(sessionId)).isEmpty();
        assertNoLegacyFrames(hostSetup);

        // ---------- Assert 2 — host accepts; engine ships session.open ----------
        sendHost(hostSetup, """
                { "protocolVersion": 1, "messageId": "m-acc", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(Instant.now(), hostSetup.instanceId(), sessionId,
                sessionId, sessionId, Instant.now()));

        JsonNode sessionOpen = awaitFrame(hostSetup, "session.open");
        JsonNode openPayload = sessionOpen.path("payload");
        assertThat(openPayload.path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(openPayload.path("serviceType").asText()).isEqualTo("CONVERSATION");
        assertThat(openPayload.path("profileVersionId").asText())
                .isEqualTo(pinVersion.getAgentProfileVersionId().toString());
        assertThat(openPayload.path("model").isObject()).isTrue();
        assertThat(openPayload.path("tools").isArray()).isTrue();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_INITIALIZING);

        // ---------- Assert 3 — host opens; engine ships the assembled execution.start ----------
        sendHost(hostSetup, """
                { "protocolVersion": 1, "messageId": "m-opd", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(Instant.now(), hostSetup.instanceId(), sessionId, sessionId));

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getAllocationState())
                .isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);

        JsonNode start = awaitFrame(hostSetup, "execution.start");
        UUID executionId = UUID.fromString(start.path("executionId").asText());
        assertThat(start.path("sessionId").asText()).isEqualTo(sessionId.toString());
        JsonNode startPayload = start.path("payload");
        assertThat(startPayload.path("requestId").asText()).isEqualTo(conv.getId().toString());
        // The USER turn sits at sequence 0, so this assistant turn is 1.
        assertThat(startPayload.path("sequenceNo").asInt()).isEqualTo(1);
        assertThat(startPayload.path("input").path("messages").isArray()).isTrue();
        assertThat(startPayload.path("input").path("messages").size()).isGreaterThanOrEqualTo(2);
        assertThat(startPayload.path("toolPolicy").path("approvalMode").asText())
                .isEqualTo("ENGINE");
        assertThat(startPayload.path("output").path("stream").asBoolean()).isTrue();
        assertThat(executionRepository.findById(executionId).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.STARTING);
        assertNoLegacyFrames(hostSetup);

        // ---------- Act 2 — host admits the execution and streams a reply ----------
        sendHost(hostSetup, """
                { "protocolVersion": 1, "messageId": "m-accept-exec", "type": "execution.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "startedAt": "%s", "resolvedModelId": "m1",
                  "dispatchId": "%s", "assignmentDigest": "abc" } }
                """.formatted(Instant.now(), hostSetup.instanceId(), sessionId, executionId,
                executionId, Instant.now(), UUID.randomUUID()));
        assertThat(executionRepository.findById(executionId).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.RUNNING);

        for (int i = 0; i < 2; i++) {
            sendHost(hostSetup, """
                    { "protocolVersion": 1, "messageId": "m-delta-%d", "type": "execution.delta",
                      "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                      "payload": { "executionId": "%s", "index": %d, "content": "tok%d", "contentType": "text" } }
                    """.formatted(i, Instant.now(), hostSetup.instanceId(), sessionId, executionId,
                    executionId, i, i));
        }

        List<String> deltaFrames = awaitViewerFrames(viewerInbound, "message.delta", 2);
        for (int i = 0; i < deltaFrames.size(); i++) {
            JsonNode delta = mapper.readTree(deltaFrames.get(i));
            assertThat(delta.path("payload").path("conversationId").asText())
                    .isEqualTo(conv.getId().toString());
            assertThat(delta.path("payload").path("deltaIndex").asInt()).isEqualTo(i);
        }

        sendHost(hostSetup, """
                { "protocolVersion": 1, "messageId": "m-term", "type": "execution.complete",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s", "executionId": "%s",
                  "payload": { "executionId": "%s", "completedAt": "%s",
                  "result": { "content": "echo from agent", "structured": null, "artifacts": [] },
                  "usage": { "modelId": "m1", "inputTokens": 3, "outputTokens": 5, "durationMs": 100 } } }
                """.formatted(Instant.now(), hostSetup.instanceId(), sessionId, executionId,
                executionId, Instant.now()));

        // ---------- Assert 4 — terminal state, viewer fan-out, persisted row ----------
        assertThat(executionRepository.findById(executionId).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.COMPLETED);

        List<String> completeFrames = awaitViewerFrames(viewerInbound, "message.complete", 1);
        JsonNode complete = mapper.readTree(completeFrames.get(0));
        assertThat(complete.path("payload").path("conversationId").asText())
                .isEqualTo(conv.getId().toString());
        assertThat(complete.path("payload").path("content").asText())
                .isEqualTo("echo from agent");

        var saved = conversationService.listMessages(conv.getId());
        assertThat(saved)
                .as("USER + ASSISTANT rows must both be persisted")
                .extracting(ConversationMessage::getRole)
                .containsSequence(ConversationMessage.Role.USER, ConversationMessage.Role.ASSISTANT);
        ConversationMessage assistant = saved.get(saved.size() - 1);
        assertThat(assistant.getContent()).isEqualTo("echo from agent");
        assertThat(assistant.getAuthorAgentId()).isEqualTo(hostSetup.host().getId());
        assertThat(assistant.getSequenceNo()).isEqualTo(1L);
        // The viewer's complete frame carries the persisted row identity.
        assertThat(complete.path("payload").path("messageId").asText())
                .isEqualTo(assistant.getId().toString());

        // ---------- Assert 5 — the whole loop rode the unified wire ----------
        assertNoLegacyFrames(hostSetup);
        assertThat(viewerInbound).allSatisfy(frame -> {
            assertThat(frame).doesNotContain("inference.");
            assertThat(frame).doesNotContain("agent.bind");
            assertThat(frame).doesNotContain("conversation.attach");
        });

        // ---------- Cleanup ----------
        broker.unsubscribe(conv.getId(), viewerSubscriber);
    }

    // ------------------------------------------------------------------
    // host-control socket harness
    // ------------------------------------------------------------------

    /**
     * A real host with a live OPEN instance: the mock session is registered
     * with the handler by a genuine {@code host.open}, so every subsequent
     * frame travels the committed handshake path.
     */
    private Host openedHost() throws Exception {
        AgentProfile profile = data.agentProfile().named("loop-profile")
                .withSystemPrompt("You are a loop tester.").create();
        AgentHostCreationResult created = data.agent()
                .named("loop-agent").withMaxAgents(10).create();
        AgentHost host = created.agent();

        BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("loop-host-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            outbound.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 4,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        ((WebSocketHandler) hostHandler).handleMessage(session, new TextMessage(open));

        JsonNode opened = awaitFrame(outbound, "host.opened");
        UUID instanceId = UUID.fromString(opened.path("payload").path("hostInstanceId").asText());
        return new Host(host, profile, session, instanceId, outbound);
    }

    /** Drive one inbound host frame through the real handler. */
    private void sendHost(Host hostSetup, String frame) throws Exception {
        ((WebSocketHandler) hostHandler).handleMessage(hostSetup.session(), new TextMessage(frame));
    }

    private JsonNode awaitFrame(Host hostSetup, String type) {
        return awaitFrame(hostSetup.outbound(), type);
    }

    /** The first engine→host frame of {@code type} seen on the socket. */
    private JsonNode awaitFrame(BlockingQueue<String> outbound, String type) {
        for (String frame : outbound) {
            try {
                JsonNode json = mapper.readTree(frame);
                if (type.equals(json.path("type").asText())) {
                    return json;
                }
            } catch (Exception e) {
                throw new IllegalStateException("unparseable host frame: " + frame, e);
            }
        }
        throw new AssertionError("no '" + type + "' frame on the host socket; saw: " + outbound);
    }

    /** {@code count} viewer frames of {@code type}, in arrival order. */
    private List<String> awaitViewerFrames(BlockingQueue<String> viewerInbound, String type, int count) {
        List<String> seen = new ArrayList<>();
        for (String frame : viewerInbound) {
            try {
                if (type.equals(mapper.readTree(frame).path("type").asText())) {
                    seen.add(frame);
                    if (seen.size() == count) {
                        return seen;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("unparseable viewer frame: " + frame, e);
            }
        }
        throw new AssertionError("expected " + count + " '" + type + "' viewer frame(s); saw: " + seen);
    }

    /** No legacy bind/attach/assign frame appeared on the control socket. */
    private void assertNoLegacyFrames(Host hostSetup) {
        for (String frame : hostSetup.outbound()) {
            for (String legacy : LEGACY_TYPES) {
                assertThat(frame)
                        .as("the unified path must never emit the legacy '%s' frame", legacy)
                        .doesNotContain("\"" + legacy + "\"");
            }
        }
    }
}
