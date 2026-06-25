package ai.myrmec.engine.websocket;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.node.EngineNode;
import ai.myrmec.engine.node.EngineNodeRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Slice 4c-1 — conversation-attach handshake. Drives the
 * {@link AgentConversationWebSocketHandler} the same way the WS runtime
 * would: a reserved worker opens its conversation socket and sends
 * {@code conversation.attach}. Asserts the engine flips the worker to
 * {@code BOUND}, pins the home node on both the worker and the
 * conversation, and registers the socket in the per-replica
 * {@link ConversationSocketRegistry}.
 *
 * <p>Turn + stream traffic still rides the control socket in 4c-1; this
 * test only covers establishing + registering the conversation socket.</p>
 */
class AgentConversationAttachE2ETest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AgentRepository agentInstanceRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private AgentConversationWebSocketHandler conversationHandler;

    @Autowired
    private ConversationSocketRegistry conversationSocketRegistry;

    @Autowired
    private NodeRegistryService nodeRegistry;

    @Autowired
    private EngineNodeRepository engineNodeRepository;

    @Test
    void conversationAttachFlipsWorkerToBoundAndRegistersSocket() throws Exception {
        // ---------- Arrange ----------
        ensureSelfNodeRegistered();
        Project project = data.project().named("attach-e2e").create();
        AgentProfile profile = data.agentProfile()
                .named("attach-profile")
                .withSystemPrompt("You are an attach tester.")
                .create();
        AgentCreationResult created = data.agent()
                .named("attach-agent")
                .withProfile(profile)
                .inProject(project)
                .create();
        AgentHost agent = created.agent();

        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "attach conv", agent.getId(), null);

        // A warm worker reserved for this conversation (IDLE → RESERVED),
        // mirroring what the dispatcher does before agent.bind.
        Agent instance = new Agent();
        instance.setAgentHostId(agent.getId());
        instance.setHostname("attach-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.RESERVED);
        instance.setConversationId(conv.getId());
        instance.setProfileVersionId(profile.getId());
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);
        UUID instanceId = instance.getId();

        WebSocketSession session = stubSession("conv-sock", instanceId);

        String attachFrame = "{\"type\":\"conversation.attach\",\"payload\":{"
                + "\"agentId\":\"" + instanceId + "\","
                + "\"conversationId\":\"" + conv.getId() + "\"}}";

        // ---------- Act ----------
        WebSocketHandler asHandler = conversationHandler;
        asHandler.handleMessage(session, new TextMessage(attachFrame));

        // ---------- Assert — worker flipped to BOUND, home node pinned ----------
        Agent reloaded = agentInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.BOUND);
        assertThat(reloaded.getHomeNodeId()).isEqualTo(nodeRegistry.getSelfNodeId());

        // ---------- Assert — conversation pinned to home node + host ----------
        Conversation convReloaded = conversationRepository.findById(conv.getId()).orElseThrow();
        assertThat(convReloaded.getHomeNodeId()).isEqualTo(nodeRegistry.getSelfNodeId());
        assertThat(convReloaded.getAgentHostId()).isEqualTo(agent.getId());

        // ---------- Assert — socket registered on this replica ----------
        assertThat(conversationSocketRegistry.isAttached(conv.getId())).isTrue();
        assertThat(conversationSocketRegistry.getSession(conv.getId())).contains(session);

        // ---------- Cleanup ----------
        conversationSocketRegistry.unregisterBySession(session);
    }

    @Test
    void conversationAttachForUnreservedWorkerIsRejectedAndClosed() throws Exception {
        // ---------- Arrange ----------
        Project project = data.project().named("attach-reject-e2e").create();
        AgentProfile profile = data.agentProfile()
                .named("attach-reject-profile")
                .withSystemPrompt("nope")
                .create();
        AgentCreationResult created = data.agent()
                .named("attach-reject-agent")
                .withProfile(profile)
                .inProject(project)
                .create();
        AgentHost agent = created.agent();

        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID, "reject conv", agent.getId(), null);

        // A warm IDLE worker that was never reserved for this conversation.
        Agent instance = new Agent();
        instance.setAgentHostId(agent.getId());
        instance.setHostname("attach-reject-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.IDLE);
        instance.setRegisteredAt(Instant.now());
        instance = agentInstanceRepository.save(instance);
        UUID instanceId = instance.getId();

        WebSocketSession session = stubSession("conv-sock-reject", instanceId);

        String attachFrame = "{\"type\":\"conversation.attach\",\"payload\":{"
                + "\"agentId\":\"" + instanceId + "\","
                + "\"conversationId\":\"" + conv.getId() + "\"}}";

        // ---------- Act ----------
        WebSocketHandler asHandler = conversationHandler;
        asHandler.handleMessage(session, new TextMessage(attachFrame));

        // ---------- Assert — rejected: worker untouched, socket closed, not registered ----------
        Agent reloaded = agentInstanceRepository.findById(instanceId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Agent.Status.IDLE);
        assertThat(conversationSocketRegistry.isAttached(conv.getId())).isFalse();
        verify(session).close(any(CloseStatus.class));
    }

    /**
     * The e2e profile disables {@link NodeRegistryService}, so the self
     * {@code engine_nodes} row the {@code home_node_id} FK references is
     * never written on startup. Seed it idempotently here.
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
     * Mockito stub for a conversation-socket session. The
     * {@code agentInstanceId} attribute mirrors what the agent-control
     * handshake interceptor pins from the JWT.
     */
    private WebSocketSession stubSession(String sessionId, UUID agentInstanceId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(AgentWebSocketHandshakeInterceptor.ATTR_AGENT_INSTANCE_ID, agentInstanceId);
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(attrs);
        return session;
    }
}
