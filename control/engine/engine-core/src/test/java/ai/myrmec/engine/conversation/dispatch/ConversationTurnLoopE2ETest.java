package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationParticipant;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.ConversationSubscriber;
import ai.myrmec.engine.node.EngineNode;
import ai.myrmec.engine.node.EngineNodeRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentConversationWebSocketHandler;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.ConversationSocketRegistry;
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
    private AgentRepository agentInstanceRepository;

    @Autowired
    private AgentConnectionManager connectionManager;

    @Autowired
    private AgentWebSocketHandler agentWebSocketHandler;

    @Autowired
    private AgentConversationWebSocketHandler conversationHandler;

    @Autowired
    private ConversationSocketRegistry conversationSocketRegistry;

    @Autowired
    private NodeRegistryService nodeRegistry;

    @Autowired
    private EngineNodeRepository engineNodeRepository;

    @Autowired
    private ConversationStreamBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userMessagePostDispatchesTurnAndAgentReplyFansOutToViewer() throws Exception {
        // ---------- Arrange ----------
        ensureSelfNodeRegistered();
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
        AgentHost agent = created.agent();

        // Seed an IDLE agent instance + register a stub WS session so
        // dispatcher.findByAgentIdAndStatus(IDLE) + isAgentIdle pass.
        Agent instance = new Agent();
        instance.setAgentHostId(agent.getId());
        instance.setHostname("loop-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
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
        ConversationSubscriber viewerSubscriber = new ConversationSubscriber() {
            private final String id = "viewer-stub-" + UUID.randomUUID();
            @Override public String id() { return id; }
            @Override public boolean isOpen() { return true; }
            @Override public void send(String jsonFrame) { viewerInbound.add(jsonFrame); }
        };
        broker.subscribe(conv.getId(), viewerSubscriber);

        // ---------- Act 1 \u2014 POST USER message via REST ----------
        ResponseEntity<String> postResponse = restTemplate.exchange(
                "/api/v1/conversations/" + conv.getId() + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(new PostUserMessageRequest("ping from user"), adminHeaders()),
                String.class);
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // ---------- Assert 1a — agent stub got the bind frame first ----------
        // Slice 3b: the dispatcher reserves the worker (IDLE → RESERVED) and
        // sends agent.bind before the turn, pinning conversation + profile.
        String bindFrame = agentOutbound.poll(3, TimeUnit.SECONDS);
        assertThat(bindFrame)
                .as("dispatcher must reserve + bind the worker before the turn")
                .isNotNull();
        JsonNode bindJson = objectMapper.readTree(bindFrame);
        assertThat(bindJson.path("type").asText()).isEqualTo("agent.bind");
        assertThat(bindJson.path("payload").path("conversationId").asText())
                .isEqualTo(conv.getId().toString());
        assertThat(bindJson.path("payload").path("homeNodeId").asText())
                .as("bind tells the worker which home node to dial for its socket")
                .isEqualTo(nodeRegistry.getSelfNodeId());

        // The dispatcher reserved the worker; the turn is buffered until the
        // conversation socket attaches.
        assertThat(agentInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .as("worker is RESERVED, turn buffered awaiting attach")
                .isEqualTo(Agent.Status.RESERVED);

        // ---------- Assert 1b — then the turn assign frame ----------
        BlockingQueue<String> convOutbound = new LinkedBlockingQueue<>();
        WebSocketSession convSession = buildStubSession(
                "agent-conv-stub", instance.getId(), "loop-agent", convOutbound);
        String attachFrame = "{\"type\":\"conversation.attach\",\"payload\":{"
                + "\"agentId\":\"" + instance.getId() + "\","
                + "\"conversationId\":\"" + conv.getId() + "\"}}";
        ((WebSocketHandler) conversationHandler)
                .handleMessage(convSession, new TextMessage(attachFrame));

        String assignFrame = convOutbound.poll(3, TimeUnit.SECONDS);
        assertThat(assignFrame)
                .as("engine must flush the buffered turn over the conversation socket")
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

        assertThat(agentInstanceRepository.findById(instance.getId()).orElseThrow().getStatus())
                .as("attach flips the reserved worker to BOUND")
                .isEqualTo(Agent.Status.BOUND);

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

        WebSocketHandler asHandler = conversationHandler;
        asHandler.handleMessage(convSession, new TextMessage(deltaFrame));
        asHandler.handleMessage(convSession, new TextMessage(completeFrame));

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
        broker.unsubscribe(conv.getId(), viewerSubscriber);
        conversationSocketRegistry.unregisterBySession(convSession);
        connectionManager.unregister(agentSession);
    }

    /**
     * The e2e profile disables {@code NodeRegistryService}, so the self
     * {@code engine_nodes} row the {@code home_node_id} FK references is never
     * written on startup. Seed it idempotently before any attach pins a home
     * node onto the worker / conversation.
     */
    private void ensureSelfNodeRegistered() {
        String selfId = nodeRegistry.getSelfNodeId();
        if (engineNodeRepository.findById(selfId).isEmpty()) {
            EngineNode node = new EngineNode();
            node.setNodeId(selfId);
            node.setAddress(nodeRegistry.getSelfAddress());
            node.setStatus(EngineNode.Status.UP);
            node.setStartedAt(Instant.now());
            node.setLastHeartbeatAt(Instant.now());
            engineNodeRepository.save(node);
        }
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
