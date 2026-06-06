package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationParticipant;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 6e \u2014 end-to-end loop covering Phase 6c + 6d + agent reply.
 *
 * <p>This test stitches together every wire hop the conversational flow
 * uses without spinning up a real agent process: REST POST \u2192
 * {@code ConversationTurnDispatcher} \u2192
 * {@link AgentWebSocketHandler#sendConversationTurn} \u2192 outbound WS
 * frame; then the simulated agent's {@code message.delta} +
 * {@code message.complete} frames are pushed back through
 * {@link AgentWebSocketHandler#handleTextMessage} and the test asserts
 * that the {@link ConversationStreamBroker} fans them to a subscribed
 * viewer session.</p>
 *
 * <p>Two stub {@link WebSocketSession}s are wired in directly:</p>
 * <ul>
 *   <li>An <b>agent session</b> registered with
 *   {@link AgentConnectionManager#register} so the dispatcher resolves
 *   an idle instance.</li>
 *   <li>A <b>viewer session</b> subscribed to the broker so we capture
 *   the broadcast frames.</li>
 * </ul>
 *
 * <p>A full real-WS test would add value but also a non-trivial cost
 * (the existing Phase 6c-2 {@code UserConversationWebSocketIntegrationTest}
 * already covers the user-side handshake + frame delivery). This test
 * focuses on the new wire glue introduced by Phase 6d/6e.</p>
 */
class ConversationTurnLoopE2ETest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AgentInstanceRepository agentInstanceRepository;

    @Autowired
    private AgentConnectionManager connectionManager;

    @Autowired
    private AgentWebSocketHandler agentWebSocketHandler;

    @Autowired
    private ConversationStreamBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userMessagePostDispatchesTurnAndAgentReplyFansOutToViewer() throws Exception {
        // ---------- Arrange ----------
        Project project = data.project().named("turn-loop-e2e").create();
        AgentProfile profile = data.agentProfile()
                .named("loop-profile")
                .withSystemPrompt("You are a loop tester.")
                .create();
        AgentCreationResult created = data.agent()
                .named("loop-agent")
                .withProfile(profile)
                .inProject(project)
                .create();
        Agent agent = created.agent();

        // Seed an ONLINE agent instance + register a stub WS session so
        // dispatcher.findByAgentIdAndStatus(ONLINE) + isAgentIdle pass.
        AgentInstance instance = new AgentInstance();
        instance.setAgentId(agent.getId());
        instance.setHostname("loop-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(AgentInstance.Status.ONLINE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);

        BlockingQueue<String> agentOutbound = new LinkedBlockingQueue<>();
        WebSocketSession agentSession = buildStubSession(
                "agent-stub", instance.getId(), "loop-agent", agentOutbound);
        connectionManager.register(instance.getId(), "loop-agent", agentSession);

        // Conversation pinned to the agent + admin participant for ACL.
        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID,
                "loop conv", agent.getId(), null);
        conversationService.addParticipant(
                conv.getId(), TEST_ADMIN_ID, ConversationParticipant.Role.OWNER);

        // Viewer session subscribed to the broker so the agent's replies
        // can be observed flowing through.
        BlockingQueue<String> viewerInbound = new LinkedBlockingQueue<>();
        WebSocketSession viewerSession = buildStubSession(
                "viewer-stub", null, null, viewerInbound);
        broker.subscribe(conv.getId(), viewerSession);

        // ---------- Act 1 \u2014 POST USER message via REST ----------
        ResponseEntity<String> postResponse = restTemplate.exchange(
                "/api/v1/conversations/" + conv.getId() + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("ping from user"), adminHeaders()),
                String.class);
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // ---------- Assert 1 \u2014 agent stub got the turn assign frame ----------
        String assignFrame = agentOutbound.poll(3, TimeUnit.SECONDS);
        assertThat(assignFrame)
                .as("dispatcher must push conversation.turn.assign to the idle agent")
                .isNotNull();
        JsonNode assignJson = objectMapper.readTree(assignFrame);
        assertThat(assignJson.path("type").asText()).isEqualTo("conversation.turn.assign");
        JsonNode payload = assignJson.path("payload");
        assertThat(payload.path("conversationId").asText()).isEqualTo(conv.getId().toString());
        assertThat(payload.path("agentId").asText()).isEqualTo(agent.getId().toString());
        assertThat(payload.path("userMessage").asText()).isEqualTo("ping from user");
        assertThat(payload.path("systemPrompt").asText()).isEqualTo("You are a loop tester.");
        long pinnedSeq = payload.path("assistantSequenceNo").asLong();
        assertThat(pinnedSeq).isGreaterThan(0);

        // ---------- Act 2 \u2014 simulate agent streaming a reply ----------
        String deltaFrame = "{\"type\":\"message.delta\",\"payload\":{"
                + "\"conversationId\":\"" + conv.getId() + "\","
                + "\"sequenceNo\":" + pinnedSeq + ","
                + "\"deltaIndex\":0,"
                + "\"content\":\"echo \"}}";
        String completeFrame = "{\"type\":\"message.complete\",\"payload\":{"
                + "\"conversationId\":\"" + conv.getId() + "\","
                + "\"sequenceNo\":" + pinnedSeq + ","
                + "\"content\":\"echo from agent\"}}";

        // Reach into the handler the same way an incoming WS frame would.
        // handleTextMessage is protected; the public WebSocketHandler.handleMessage
        // entry point delegates to it identically to how Spring's WS runtime does.
        WebSocketHandler asHandler = agentWebSocketHandler;
        asHandler.handleMessage(agentSession, new TextMessage(deltaFrame));
        asHandler.handleMessage(agentSession, new TextMessage(completeFrame));

        // ---------- Assert 2 \u2014 viewer received both frames verbatim ----------
        String firstSeen = viewerInbound.poll(2, TimeUnit.SECONDS);
        String secondSeen = viewerInbound.poll(2, TimeUnit.SECONDS);
        assertThat(firstSeen).as("delta must reach the viewer").isEqualTo(deltaFrame);
        assertThat(secondSeen).as("complete must reach the viewer").isEqualTo(completeFrame);

        // ---------- Assert 3 \u2014 assistant message persisted for replay ----------
        var saved = conversationService.listMessages(conv.getId());
        assertThat(saved)
                .as("USER + ASSISTANT rows must both be persisted")
                .extracting(ConversationMessage::getRole)
                .containsSequence(ConversationMessage.Role.USER, ConversationMessage.Role.ASSISTANT);
        ConversationMessage assistant = saved.get(saved.size() - 1);
        assertThat(assistant.getContent()).isEqualTo("echo from agent");
        assertThat(assistant.getAuthorAgentId()).isEqualTo(agent.getId());

        // ---------- Cleanup ----------
        broker.unsubscribe(conv.getId(), viewerSession);
        connectionManager.unregister(agentSession);
    }

    /**
     * Mockito stub for a WebSocketSession that captures outbound text
     * frames into {@code outbound}. The session ID + attributes mirror
     * what the real handshake would populate so any path that reads
     * {@code ATTR_AGENT_INSTANCE_ID} works against the stub.
     */
    private WebSocketSession buildStubSession(
            String sessionId,
            UUID agentInstanceId,
            String agentName,
            BlockingQueue<String> outbound) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        if (agentInstanceId != null) {
            attrs.put("agentInstanceId", agentInstanceId);
            attrs.put("agentName", agentName);
        }
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            outbound.add(msg.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }
}
