package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationNoticeService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #87 — verifies the {@link ConversationBacklogDrainer} re-dispatches
 * conversations with a pending (unanswered, un-bound) USER turn when a worker
 * comes online, and leaves in-flight or already-answered threads alone.
 */
class ConversationBacklogDrainerTest {

    private AgentRepository agentInstanceRepository;
    private ConversationRepository conversationRepository;
    private ConversationService conversationService;
    private ConversationNoticeService conversationNoticeService;
    private ConversationTurnDispatcher turnDispatcher;

    private ConversationBacklogDrainer drainer;

    private final UUID instanceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentInstanceRepository = mock(AgentRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        conversationService = mock(ConversationService.class);
        conversationNoticeService = mock(ConversationNoticeService.class);
        turnDispatcher = mock(ConversationTurnDispatcher.class);

        drainer = new ConversationBacklogDrainer(
                agentInstanceRepository,
                conversationRepository,
                conversationService,
                conversationNoticeService,
                turnDispatcher);

        Agent worker = new Agent();
        worker.setId(instanceId);
        worker.setAgentHostId(hostId);
        when(agentInstanceRepository.findById(instanceId)).thenReturn(Optional.of(worker));

        // Real no-agent-notice recognition: SYSTEM row tagged with the marker.
        when(conversationNoticeService.isNoAgentNotice(any())).thenAnswer(inv -> {
            ConversationMessage m = inv.getArgument(0);
            return m != null
                    && m.getRole() == ConversationMessage.Role.SYSTEM
                    && m.getPayloadJson() != null
                    && m.getPayloadJson().contains("NO_AGENT_NOTICE");
        });
    }

    @Test
    void redispatchesConversationWithPendingUserTurn() {
        UUID convId = UUID.randomUUID();
        Conversation conv = conversation(convId);
        when(conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE))
                .thenReturn(List.of(conv));
        when(agentInstanceRepository.countByConversationIdAndStatusIn(eq(convId), any()))
                .thenReturn(0L);
        when(conversationService.listMessages(convId)).thenReturn(List.of(
                msg(convId, 0L, ConversationMessage.Role.USER, null)));
        when(turnDispatcher.dispatch(convId)).thenReturn(true);

        drainer.onAgentAvailable(new AgentAvailableEvent(instanceId));

        verify(turnDispatcher).dispatch(convId);
    }

    @Test
    void treatsTrailingNoAgentNoticeAsPending() {
        UUID convId = UUID.randomUUID();
        Conversation conv = conversation(convId);
        when(conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE))
                .thenReturn(List.of(conv));
        when(agentInstanceRepository.countByConversationIdAndStatusIn(eq(convId), any()))
                .thenReturn(0L);
        when(conversationService.listMessages(convId)).thenReturn(List.of(
                msg(convId, 0L, ConversationMessage.Role.USER, null),
                msg(convId, 1L, ConversationMessage.Role.SYSTEM, "{\"kind\":\"NO_AGENT_NOTICE\"}")));
        when(turnDispatcher.dispatch(convId)).thenReturn(true);

        drainer.onAgentAvailable(new AgentAvailableEvent(instanceId));

        verify(turnDispatcher).dispatch(convId);
    }

    @Test
    void skipsConversationAlreadyInFlight() {
        UUID convId = UUID.randomUUID();
        Conversation conv = conversation(convId);
        when(conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE))
                .thenReturn(List.of(conv));
        // A worker already holds a binding to this conversation.
        when(agentInstanceRepository.countByConversationIdAndStatusIn(eq(convId), any()))
                .thenReturn(1L);

        drainer.onAgentAvailable(new AgentAvailableEvent(instanceId));

        verify(turnDispatcher, never()).dispatch(any());
    }

    @Test
    void skipsConversationAlreadyAnswered() {
        UUID convId = UUID.randomUUID();
        Conversation conv = conversation(convId);
        when(conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE))
                .thenReturn(List.of(conv));
        when(agentInstanceRepository.countByConversationIdAndStatusIn(eq(convId), any()))
                .thenReturn(0L);
        when(conversationService.listMessages(convId)).thenReturn(List.of(
                msg(convId, 0L, ConversationMessage.Role.USER, null),
                msg(convId, 1L, ConversationMessage.Role.ASSISTANT, null)));

        drainer.onAgentAvailable(new AgentAvailableEvent(instanceId));

        verify(turnDispatcher, never()).dispatch(any());
    }

    @Test
    void stopsAfterFirstSuccessfulDispatch() {
        UUID conv1 = UUID.randomUUID();
        UUID conv2 = UUID.randomUUID();
        when(conversationRepository.findByAgentIdAndStatus(hostId, Conversation.Status.ACTIVE))
                .thenReturn(List.of(conversation(conv1), conversation(conv2)));
        when(agentInstanceRepository.countByConversationIdAndStatusIn(any(), any()))
                .thenReturn(0L);
        when(conversationService.listMessages(any())).thenReturn(List.of(
                msg(conv1, 0L, ConversationMessage.Role.USER, null)));
        when(turnDispatcher.dispatch(conv1)).thenReturn(true);

        drainer.onAgentAvailable(new AgentAvailableEvent(instanceId));

        verify(turnDispatcher).dispatch(conv1);
        verify(turnDispatcher, never()).dispatch(conv2);
    }

    private Conversation conversation(UUID id) {
        Conversation conv = new Conversation();
        conv.setId(id);
        conv.setAgentId(hostId);
        return conv;
    }

    private ConversationMessage msg(UUID convId, long seq, ConversationMessage.Role role, String payloadJson) {
        ConversationMessage m = new ConversationMessage();
        m.setConversationId(convId);
        m.setSequenceNo(seq);
        m.setRole(role);
        m.setPayloadJson(payloadJson);
        return m;
    }
}
