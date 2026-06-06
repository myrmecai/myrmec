package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6d \u2014 verifies the {@link ConversationTurnDispatcher} assembles
 * the right context window and routes it to an idle agent instance
 * through {@link AgentWebSocketHandler#sendConversationTurn}.
 *
 * <p>Kept as a pure Mockito test (no Spring boot) to stay cheap. The
 * dispatcher has no JPA / transactional behaviour worth booting a
 * context for; the wiring is exercised end-to-end by the controller
 * test in {@code ConversationControllerTest}.</p>
 */
class ConversationTurnDispatcherTest {

    private ConversationRepository conversationRepository;
    private ConversationService conversationService;
    private AgentRepository agentRepository;
    private AgentProfileRepository agentProfileRepository;
    private AgentInstanceRepository agentInstanceRepository;
    private AgentConnectionManager connectionManager;
    private AgentWebSocketHandler webSocketHandler;
    private ModelService modelService;
    private ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;

    private ConversationTurnDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        conversationService = mock(ConversationService.class);
        agentRepository = mock(AgentRepository.class);
        agentProfileRepository = mock(AgentProfileRepository.class);
        agentInstanceRepository = mock(AgentInstanceRepository.class);
        connectionManager = mock(AgentConnectionManager.class);
        webSocketHandler = mock(AgentWebSocketHandler.class);
        modelService = mock(ModelService.class);
        snapshotWriter = mock(ai.myrmec.engine.snapshot.SnapshotWriter.class);

        dispatcher = new ConversationTurnDispatcher(
                conversationRepository,
                conversationService,
                agentRepository,
                agentProfileRepository,
                agentInstanceRepository,
                connectionManager,
                webSocketHandler,
                modelService,
                snapshotWriter);
    }

    @Test
    void dispatchesTurnToIdleAgentWithAssembledContext() {
        UUID conversationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(projectId);
        conv.setAgentId(agentId);
        conv.setSystemPromptOverride("Custom override.");
        conv.setPinnedFacts("Pin one fact.");

        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        // No defaultModel \u2014 dispatcher skips model resolution and ships null modelInfo.

        AgentInstance instance = new AgentInstance();
        instance.setId(instanceId);
        instance.setAgentId(agentId);
        instance.setStatus(AgentInstance.Status.ONLINE);

        ConversationMessage user0 = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "Hi");
        ConversationMessage asst1 = newMessage(conversationId, 1L, ConversationMessage.Role.ASSISTANT, "Hello!");
        ConversationMessage user2 = newMessage(conversationId, 2L, ConversationMessage.Role.USER, "How are you?");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentIdAndStatus(agentId, AgentInstance.Status.ONLINE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(user0, asst1, user2));
        when(webSocketHandler.sendConversationTurn(eq(instanceId), any())).thenReturn(true);

        boolean dispatched = dispatcher.dispatch(conversationId);

        assertThat(dispatched).isTrue();

        ArgumentCaptor<ConversationTurnAssignPayload> captor =
                ArgumentCaptor.forClass(ConversationTurnAssignPayload.class);
        verify(webSocketHandler).sendConversationTurn(eq(instanceId), captor.capture());

        ConversationTurnAssignPayload payload = captor.getValue();
        assertThat(payload.getConversationId()).isEqualTo(conversationId);
        assertThat(payload.getProjectId()).isEqualTo(projectId);
        assertThat(payload.getAgentId()).isEqualTo(agentId);
        // System-prompt override wins over the profile default.
        assertThat(payload.getSystemPrompt()).isEqualTo("Custom override.");
        assertThat(payload.getPinnedFacts()).isEqualTo("Pin one fact.");
        // assistantSequenceNo is one past the last persisted row (USER seq 2 \u2192 ASSISTANT seq 3).
        assertThat(payload.getAssistantSequenceNo()).isEqualTo(3L);
        // userMessage convenience field carries the most-recent USER content.
        assertThat(payload.getUserMessage()).isEqualTo("How are you?");
        // History preserves order + role + sequence_no.
        assertThat(payload.getHistory()).hasSize(3);
        assertThat(payload.getHistory().get(0).getRole()).isEqualTo("USER");
        assertThat(payload.getHistory().get(0).getSequenceNo()).isEqualTo(0L);
        assertThat(payload.getHistory().get(2).getContent()).isEqualTo("How are you?");
        // No model configured \u2192 modelInfo is null but the turn still ships.
        assertThat(payload.getModel()).isNull();
        assertThat(payload.getTimeoutSeconds()).isEqualTo(ConversationTurnDispatcher.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void fallsBackToProfileSystemPromptWhenOverrideNotSet() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(UUID.randomUUID());
        conv.setAgentId(agentId);
        // No override.
        conv.setSystemPromptOverride(null);

        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");

        AgentInstance instance = new AgentInstance();
        instance.setId(instanceId);
        instance.setAgentId(agentId);
        instance.setStatus(AgentInstance.Status.ONLINE);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentIdAndStatus(agentId, AgentInstance.Status.ONLINE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(newMessage(conversationId, 0L,
                        ConversationMessage.Role.USER, "ping")));
        when(webSocketHandler.sendConversationTurn(eq(instanceId), any())).thenReturn(true);

        dispatcher.dispatch(conversationId);

        ArgumentCaptor<ConversationTurnAssignPayload> captor =
                ArgumentCaptor.forClass(ConversationTurnAssignPayload.class);
        verify(webSocketHandler).sendConversationTurn(eq(instanceId), captor.capture());
        assertThat(captor.getValue().getSystemPrompt()).isEqualTo("Profile default prompt.");
    }

    @Test
    void returnsFalseAndSendsNothingWhenNoIdleInstance() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(UUID.randomUUID());
        conv.setAgentId(agentId);

        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentInstance instance = new AgentInstance();
        instance.setId(instanceId);
        instance.setAgentId(agentId);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findById(profileId)).thenReturn(Optional.of(new AgentProfile()));
        when(agentInstanceRepository.findByAgentIdAndStatus(agentId, AgentInstance.Status.ONLINE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(false);

        boolean dispatched = dispatcher.dispatch(conversationId);

        assertThat(dispatched).isFalse();
        verify(webSocketHandler, never()).sendConversationTurn(any(), any());
    }

    @Test
    void returnsFalseWhenConversationHasNoPinnedAgent() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(UUID.randomUUID());
        // agentId stays null \u2014 fresh conversation that hasn't picked an agent.

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));

        boolean dispatched = dispatcher.dispatch(conversationId);

        assertThat(dispatched).isFalse();
        verify(agentRepository, never()).findById(any());
        verify(webSocketHandler, never()).sendConversationTurn(any(), any());
    }

    private static ConversationMessage newMessage(
            UUID conversationId, long seq, ConversationMessage.Role role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setId(UUID.randomUUID());
        m.setConversationId(conversationId);
        m.setSequenceNo(seq);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(Instant.now());
        return m;
    }
}
