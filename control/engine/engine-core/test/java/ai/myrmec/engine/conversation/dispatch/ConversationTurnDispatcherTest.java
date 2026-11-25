// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine._protocol.payload.ExecutionStartPayload;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.agent.HostSelectionService;
import ai.myrmec.engine.assistant.AssistantAgentBinding;
import ai.myrmec.engine.assistant.AssistantAgentBindingRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import ai.myrmec.engine.inference.execution.ExecutionInputAssembler;
import ai.myrmec.engine.inference.execution.ExecutionRegistry;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.inference.InferenceMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unified-path contract for {@link ConversationTurnDispatcher}.
 *
 * <p>The legacy {@code agent.bind}/{@code conversation.attach}/
 * {@code inference.assign} seams are gone. These tests pin the two branches that
 * replace them:</p>
 * <ul>
 *   <li><b>Reuse</b> — an ACTIVE session pinned to the selected host: the SAME
 *       session row serves the turn, with {@code execution.start} shipped
 *       immediately and NO second offer.</li>
 *   <li><b>Decline</b> — no ACTIVE session: the turn is dropped. Allocation +
 *       offer belong to {@link ConversationService#start} (the client's
 *       create&rarr;start phase), not this dispatcher.</li>
 * </ul>
 *
 * <p>Kept as a pure Mockito test (no Spring boot): the discriminating behaviour
 * is which seam each branch calls, and the handler's own flow tests already cover
 * the wire shapes.</p>
 */
class ConversationTurnDispatcherTest {

    private ConversationRepository conversationRepository;
    private ConversationService conversationService;
    private AgentProfileRepository agentProfileRepository;
    private AgentProfileVersionRepository agentProfileVersionRepository;
    private AssistantAgentBindingRepository assistantAgentBindingRepository;
    private ai.myrmec.engine.spi.quota.QuotaPolicyEngine quotaPolicyEngine;
    private ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;
    private ai.myrmec.engine.conversation.ConversationNoticeService conversationNoticeService;
    private HostSelectionService hostSelectionService;
    private AgentHostInstanceRepository hostInstanceRepository;
    private SessionAllocator sessionAllocator;
    private SessionRepository sessionRepository;
    private SessionExecutionRepository executionRepository;
    private ExecutionRegistry executionRegistry;
    private ExecutionCommandSender executionCommandSender;
    private ExecutionInputAssembler executionInputAssembler;

    private ConversationTurnDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        conversationService = mock(ConversationService.class);
        agentProfileRepository = mock(AgentProfileRepository.class);
        agentProfileVersionRepository = mock(AgentProfileVersionRepository.class);
        assistantAgentBindingRepository = mock(AssistantAgentBindingRepository.class);
        quotaPolicyEngine = mock(ai.myrmec.engine.spi.quota.QuotaPolicyEngine.class);
        snapshotWriter = mock(ai.myrmec.engine.snapshot.SnapshotWriter.class);
        conversationNoticeService = mock(ai.myrmec.engine.conversation.ConversationNoticeService.class);
        hostSelectionService = mock(HostSelectionService.class);
        hostInstanceRepository = mock(AgentHostInstanceRepository.class);
        sessionAllocator = mock(SessionAllocator.class);
        sessionRepository = mock(SessionRepository.class);
        executionRepository = mock(SessionExecutionRepository.class);
        executionRegistry = mock(ExecutionRegistry.class);
        executionCommandSender = mock(ExecutionCommandSender.class);
        executionInputAssembler = mock(ExecutionInputAssembler.class);

        when(quotaPolicyEngine.check(any(), any(), any(), anyLong()))
                .thenReturn(ai.myrmec.engine.spi.quota.QuotaDecision.unconstrained());
        // The assembler's output is the §8.1 input block; a minimal transcript is
        // enough for the payload projection under test.
        when(executionInputAssembler.assembleConversationInput(any(), any(), any(), anyLong()))
                .thenReturn(Map.of(
                        "messages", List.of(new InferenceMessage("user", "hello", null, null)),
                        "toolPolicy", Map.of("activeToolNames", List.of("search"), "approvalMode", "ENGINE"),
                        "output", Map.of("stream", true, "responseSequenceNo", 1, "format", "TEXT")));

        dispatcher = new ConversationTurnDispatcher(
                conversationRepository,
                conversationService,
                agentProfileRepository,
                agentProfileVersionRepository,
                assistantAgentBindingRepository,
                quotaPolicyEngine,
                snapshotWriter,
                conversationNoticeService,
                hostSelectionService,
                hostInstanceRepository,
                sessionAllocator,
                sessionRepository,
                executionRepository,
                executionRegistry,
                executionCommandSender,
                executionInputAssembler);
    }

    // ---------------------------------------------------------------- decline

    @Test
    void declinesWhenNoActiveSessionExists() {
        UUID conversationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        Conversation conv = pinnedConversation(conversationId, teamId);
        wireHost(conv, hostId);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatch(conversationId)).isFalse();

        // Allocation + offer belong to start(): the dispatcher never mints a session.
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());
        verify(executionRegistry, never()).start(any(), any(), any(), any());
        verify(executionCommandSender, never()).startConversation(any(), any(), any());
    }

    @Test
    void declinesWithTheNoAgentNoticeWhenNoHostHasCapacity() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = pinnedConversation(conversationId, UUID.randomUUID());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(hostSelectionService.selectForTeam(conv.getTeamId())).thenReturn(Optional.empty());
        when(conversationService.listMessages(conversationId)).thenReturn(List.of());

        assertThat(dispatcher.dispatch(conversationId)).isFalse();

        verify(conversationNoticeService).emitNoAgentNotice(conversationId);
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());
    }

    @Test
    void declinesWithTheNoAgentNoticeWhenTheConversationHasNoPinnedProfileVersion() {
        UUID conversationId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setTeamId(UUID.randomUUID());
        // No assistantVersionId — an unpinned legacy conversation.

        AgentHost host = new AgentHost();
        host.setId(hostId);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(hostSelectionService.selectForTeam(conv.getTeamId())).thenReturn(Optional.of(host));

        assertThat(dispatcher.dispatch(conversationId)).isFalse();

        verify(conversationNoticeService).emitNoAgentNotice(conversationId);
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- reuse

    @Test
    void reusesTheSameActiveSessionAndShipsExecutionStartImmediately() {
        UUID conversationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Conversation conv = pinnedConversation(conversationId, teamId);
        wireHost(conv, hostId);

        Session active = new Session();
        active.setId(sessionId);
        active.setRefId(conversationId);
        active.setServiceType("CONVERSATION");
        active.setHostInstanceId(instanceId);
        active.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(active));

        AgentHostInstance instance = hostInstance(hostId);
        when(hostInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

        SessionExecution priorTurn = new SessionExecution();
        priorTurn.setSequenceNo(4);
        when(executionRepository.findBySessionIdOrderBySequenceNoDesc(sessionId))
                .thenReturn(List.of(priorTurn));
        when(executionRegistry.start(eq(sessionId), eq(conversationId.toString()), any(), any()))
                .thenReturn(Optional.of(execution(executionId, sessionId)));
        when(executionCommandSender.startConversation(eq(executionId), any(), any())).thenReturn(true);

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        // No second offer: the ACTIVE row is reused (sticky semantics).
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());

        // The execution ships immediately, with the sequence one past the prior turn.
        ArgumentCaptor<ExecutionStartPayload> payload =
                ArgumentCaptor.forClass(ExecutionStartPayload.class);
        verify(executionCommandSender).startConversation(eq(executionId), any(), payload.capture());
        assertThat(payload.getValue().executionId()).isEqualTo(executionId);
        assertThat(payload.getValue().sequenceNo()).isEqualTo(5);
        assertThat(payload.getValue().requestId()).isEqualTo(conversationId.toString());
        assertThat(payload.getValue().toolPolicy().activeToolNames()).containsExactly("search");
        assertThat(payload.getValue().output().stream()).isTrue();
    }

    @Test
    void closesAnActiveSessionPinnedToADifferentHostAndDeclines() {
        UUID conversationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID selectedHostId = UUID.randomUUID();
        UUID staleInstanceId = UUID.randomUUID();
        UUID staleHostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        Conversation conv = pinnedConversation(conversationId, teamId);
        wireHost(conv, selectedHostId);

        Session stale = new Session();
        stale.setId(sessionId);
        stale.setRefId(conversationId);
        stale.setHostInstanceId(staleInstanceId);
        stale.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(stale));

        AgentHostInstance staleInstance = hostInstance(staleHostId);
        when(hostInstanceRepository.findById(staleInstanceId)).thenReturn(Optional.of(staleInstance));

        assertThat(dispatcher.dispatch(conversationId)).isFalse();

        // The dead-host session is closed; a fresh offer is start()'s job, not here.
        verify(sessionAllocator).close(sessionId, "HOST_RESELECTED");
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());
        verify(executionRegistry, never()).start(any(), any(), any(), any());
    }

    @Test
    void refusesToShipWhenATurnIsAlreadyInFlightOnTheSession() {
        UUID conversationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        Conversation conv = pinnedConversation(conversationId, teamId);
        wireHost(conv, hostId);

        Session active = new Session();
        active.setId(sessionId);
        active.setRefId(conversationId);
        active.setHostInstanceId(instanceId);
        active.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(active));

        AgentHostInstance instance = hostInstance(hostId);
        when(hostInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

        when(executionRepository.findBySessionIdOrderBySequenceNoDesc(sessionId)).thenReturn(List.of());
        // §11.3.4 — the one-in-flight guard inside the registry refuses.
        when(executionRegistry.start(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatch(conversationId)).isFalse();

        verify(executionCommandSender, never()).startConversation(any(), any(), any());
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelShipsAnExecutionCancelForTheInFlightExecution() {
        UUID conversationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Session active = new Session();
        active.setId(sessionId);
        active.setRefId(conversationId);
        active.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(active));

        SessionExecution inFlight = execution(executionId, sessionId);
        inFlight.setState(SessionExecution.State.RUNNING);
        when(executionRepository.findWithLockBySessionIdAndStateIn(eq(sessionId), any()))
                .thenReturn(List.of(inFlight));
        when(executionCommandSender.cancel(eq(executionId), eq(active), eq(null), eq("USER_REQUESTED"), eq(5)))
                .thenReturn(true);

        assertThat(dispatcher.cancel(conversationId)).isTrue();

        verify(executionCommandSender).cancel(executionId, active, null, "USER_REQUESTED", 5);
    }

    @Test
    void cancelReturnsFalseWhenNothingIsInFlight() {
        UUID conversationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        Session active = new Session();
        active.setId(sessionId);
        active.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(active));
        when(executionRepository.findWithLockBySessionIdAndStateIn(eq(sessionId), any()))
                .thenReturn(List.of());

        assertThat(dispatcher.cancel(conversationId)).isFalse();

        verify(executionCommandSender, never()).cancel(any(), any(), any(), any(), anyInt());
    }

    @Test
    void cancelReturnsFalseWhenNoSessionIsActive() {
        UUID conversationId = UUID.randomUUID();
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.empty());

        assertThat(dispatcher.cancel(conversationId)).isFalse();

        verify(executionCommandSender, never()).cancel(any(), any(), any(), any(), anyInt());
    }

    // ---------------------------------------------------------------- summary

    @Test
    void summaryTurnReusesTheActiveSessionWithTheSummariserPrompt() {
        UUID conversationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Conversation conv = pinnedConversation(conversationId, teamId);
        wireHost(conv, hostId);

        Session active = new Session();
        active.setId(sessionId);
        active.setRefId(conversationId);
        active.setServiceType("CONVERSATION");
        active.setHostInstanceId(instanceId);
        active.setAllocationState(SessionAllocator.ALLOC_STATE_ACTIVE);
        when(sessionRepository.findByRefIdAndServiceTypeAndAllocationStateIn(
                eq(conversationId), eq("CONVERSATION"), any())).thenReturn(Optional.of(active));

        AgentHostInstance instance = hostInstance(hostId);
        when(hostInstanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

        when(executionRepository.findBySessionIdOrderBySequenceNoDesc(sessionId)).thenReturn(List.of());
        when(executionRegistry.start(eq(sessionId), eq(conversationId.toString()), any(), any()))
                .thenReturn(Optional.of(execution(executionId, sessionId)));
        when(executionCommandSender.startConversation(eq(executionId), any(), any())).thenReturn(true);

        ConversationMessage older =
                message(conversationId, 1L, ConversationMessage.Role.USER, "earlier turn");
        assertThat(dispatcher.dispatchSummary(conversationId, "prior summary", List.of(older))).isTrue();

        ArgumentCaptor<ExecutionStartPayload> payload =
                ArgumentCaptor.forClass(ExecutionStartPayload.class);
        verify(executionCommandSender).startConversation(eq(executionId), any(), payload.capture());

        List<InferenceMessage> messages = payload.getValue().input().messages();
        assertThat(messages.get(0).content()).contains("summarisation assistant");
        assertThat(messages).anySatisfy(m ->
                assertThat(String.valueOf(m.content())).contains("prior summary"));
        assertThat(messages).anySatisfy(m ->
                assertThat(String.valueOf(m.content())).contains("earlier turn"));
        // Summaries are non-tool turns: no active tools, still streaming.
        assertThat(payload.getValue().toolPolicy().activeToolNames()).isEmpty();
        assertThat(payload.getValue().output().stream()).isTrue();
    }

    @Test
    void summaryDefersWithoutTheNoAgentNoticeWhenNoHostIsAvailable() {
        UUID conversationId = UUID.randomUUID();
        Conversation conv = pinnedConversation(conversationId, UUID.randomUUID());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(hostSelectionService.selectForTeam(conv.getTeamId())).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatchSummary(conversationId, null, List.of())).isFalse();

        // A summary is an internal optimisation — no user-facing no-agent notice.
        verify(conversationNoticeService, never()).emitNoAgentNotice(any());
        verify(sessionAllocator, never()).offer(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- fixtures

    private Conversation pinnedConversation(UUID conversationId, UUID teamId) {
        UUID assistantVersionId = UUID.randomUUID();
        UUID profileVersionId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setTeamId(teamId);
        conv.setAssistantVersionId(assistantVersionId);

        AssistantAgentBinding binding = new AssistantAgentBinding();
        binding.setAgentProfileVersionId(profileVersionId);
        when(assistantAgentBindingRepository.findByAssistantVersionIdAndRoleType(
                assistantVersionId, AssistantAgentBinding.RoleType.PRIMARY))
                .thenReturn(List.of(binding));

        AgentProfile profile = new AgentProfile();
        profile.setId(profileId);
        AgentProfileVersion version = new AgentProfileVersion();
        version.setId(profileVersionId);
        version.setProfileId(profileId);
        version.setSystemPrompt("Profile prompt.");
        version.setStatus(AgentProfileVersion.Status.PUBLISHED);
        version.setTools(java.util.Set.of());
        when(agentProfileVersionRepository.findByIdWithTools(profileVersionId)).thenReturn(Optional.of(version));
        when(agentProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
        return conv;
    }

    /** Wire the conversation's host selection plus the persisted message list. */
    private void wireHost(Conversation conv, UUID hostId) {
        AgentHost host = new AgentHost();
        host.setId(hostId);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        when(hostSelectionService.selectForTeam(conv.getTeamId())).thenReturn(Optional.of(host));
        when(conversationService.listMessages(conv.getId())).thenReturn(
                List.of(message(conv.getId(), 0L, ConversationMessage.Role.USER, "ping")));
    }

    private static SessionExecution execution(UUID executionId, UUID sessionId) {
        SessionExecution execution = new SessionExecution();
        execution.setId(executionId);
        execution.setSessionId(sessionId);
        execution.setServiceType("CONVERSATION");
        execution.setState(SessionExecution.State.STARTING);
        return execution;
    }

    /**
     * A stub live instance pinned to {@code hostId}. {@link AgentHostInstance}
     * mints its id via the {@code open(...)} factory (there is no setter), so the
     * test doubles it and stubs only the association the dispatcher reads.
     */
    private static AgentHostInstance hostInstance(UUID hostId) {
        AgentHostInstance instance = mock(AgentHostInstance.class);
        when(instance.getAgentHostId()).thenReturn(hostId);
        return instance;
    }

    private static ConversationMessage message(UUID conversationId, long seq,
                                               ConversationMessage.Role role, String content) {
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
