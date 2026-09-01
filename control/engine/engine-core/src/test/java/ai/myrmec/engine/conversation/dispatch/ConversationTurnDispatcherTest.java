package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostService;
import ai.myrmec.engine.attachment.ConversationMessageAttachment;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.inference.InferenceRequestSpec;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.InferenceRequestAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private AgentHostRepository agentRepository;
    private AgentProfileRepository agentProfileRepository;
    private AgentRepository agentInstanceRepository;
    private AgentConnectionManager connectionManager;
    private AgentWebSocketHandler webSocketHandler;
    private ModelService modelService;
    private ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;
    private AgentHostService agentService;
    private ai.myrmec.engine.node.NodeRegistryService nodeRegistry;
    private PendingTurnRegistry pendingTurnRegistry;
    private ai.myrmec.engine.websocket.ConversationSocketRegistry conversationSocketRegistry;
    private ai.myrmec.engine.attachment.AttachmentService attachmentService;
    private ai.myrmec.engine.setting.SystemSettingService systemSettingService;
    private ai.myrmec.engine.conversation.ConversationNoticeService conversationNoticeService;
    private SessionContextAssembler sessionContextAssembler;
    private InferenceRequestAssembler inferenceRequestAssembler;
    private ai.myrmec.engine.governance.GovernancePolicyResolver governancePolicyResolver;

    private ConversationTurnDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        conversationService = mock(ConversationService.class);
        agentRepository = mock(AgentHostRepository.class);
        agentProfileRepository = mock(AgentProfileRepository.class);
        agentInstanceRepository = mock(AgentRepository.class);
        connectionManager = mock(AgentConnectionManager.class);
        webSocketHandler = mock(AgentWebSocketHandler.class);
        modelService = mock(ModelService.class);
        snapshotWriter = mock(ai.myrmec.engine.snapshot.SnapshotWriter.class);
        agentService = mock(AgentHostService.class);
        nodeRegistry = mock(ai.myrmec.engine.node.NodeRegistryService.class);
        pendingTurnRegistry = mock(PendingTurnRegistry.class);
        conversationSocketRegistry = mock(ai.myrmec.engine.websocket.ConversationSocketRegistry.class);
        attachmentService = mock(ai.myrmec.engine.attachment.AttachmentService.class);
        systemSettingService = mock(ai.myrmec.engine.setting.SystemSettingService.class);
        conversationNoticeService = mock(ai.myrmec.engine.conversation.ConversationNoticeService.class);
        sessionContextAssembler = mock(SessionContextAssembler.class);
        inferenceRequestAssembler = mock(InferenceRequestAssembler.class);
        governancePolicyResolver = mock(ai.myrmec.engine.governance.GovernancePolicyResolver.class);
        when(governancePolicyResolver.resolveOrgDefault())
                .thenReturn(new ai.myrmec.engine.governance.EffectivePolicy(
                        ai.myrmec.engine.governance.BuiltInGovernanceProfile.STANDARD));
        // The dispatcher always calls sessionContextAssembler.assemble(...) to
        // build the session.open frame; stub it with a non-null payload so the
        // assemble path that reads sessionOpen.sessionId() doesn't NPE.
        when(sessionContextAssembler.assemble(any(), any(), any(), any()))
                .thenReturn(new ai.myrmec.engine.websocket.message.payload.SessionOpenPayload(
                        UUID.randomUUID(), "CONVERSATION", null, null,
                        null, null, java.util.List.of(), java.util.List.of(), false));
        when(attachmentService.listForMessage(any()))
                .thenReturn(java.util.List.of());
        when(systemSettingService.getInt(any(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        ai.myrmec.engine.spi.quota.QuotaPolicyEngine quotaPolicyEngine =
                mock(ai.myrmec.engine.spi.quota.QuotaPolicyEngine.class);
        when(quotaPolicyEngine.check(any(), any(), any(), anyLong()))
                .thenReturn(ai.myrmec.engine.spi.quota.QuotaDecision.unconstrained());

        dispatcher = new ConversationTurnDispatcher(
                conversationRepository,
                conversationService,
                agentRepository,
                agentProfileRepository,
                agentInstanceRepository,
                connectionManager,
                webSocketHandler,
                modelService,
                snapshotWriter,
                quotaPolicyEngine,
                agentService,
                nodeRegistry,
                pendingTurnRegistry,
                conversationSocketRegistry,
                attachmentService,
                systemSettingService,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                conversationNoticeService,
                sessionContextAssembler,
                inferenceRequestAssembler,
                governancePolicyResolver);
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

        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        // No defaultModel \u2014 dispatcher skips model resolution and ships null modelInfo.

        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);
        instance.setStatus(Agent.Status.IDLE);

        ConversationMessage user0 = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "Hi");
        ConversationMessage asst1 = newMessage(conversationId, 1L, ConversationMessage.Role.ASSISTANT, "Hello!");
        ConversationMessage user2 = newMessage(conversationId, 2L, ConversationMessage.Role.USER, "How are you?");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(agentService.reserveInstance(eq(instanceId), any(), any()))
                .thenReturn(true);
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(user0, asst1, user2));

        boolean dispatched = dispatcher.dispatch(conversationId);

        assertThat(dispatched).isTrue();

        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler).assemble(captor.capture());

        InferenceRequestSpec spec = captor.getValue();
        assertThat(spec.getRequestId()).isEqualTo(conversationId);
        assertThat(spec.getProjectId()).isEqualTo(projectId);
        // System-prompt override wins over the profile default.
        assertThat(spec.getConversationSystemPrompt()).isEqualTo("Custom override.");
        assertThat(spec.getPinnedFacts()).isEqualTo("Pin one fact.");
        // userMessage convenience field carries the most-recent USER content.
        assertThat(spec.getUserMessage()).isEqualTo("How are you?");
        // History preserves order + role + content.
        assertThat(spec.getHistory()).hasSize(3);
        assertThat(spec.getHistory().get(0).role()).isEqualTo("USER");
        assertThat(spec.getHistory().get(2).content()).isEqualTo("How are you?");
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

        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");

        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);
        instance.setStatus(Agent.Status.IDLE);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(agentService.reserveInstance(eq(instanceId), any(), any()))
                .thenReturn(true);
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(newMessage(conversationId, 0L,
                        ConversationMessage.Role.USER, "ping")));

        dispatcher.dispatch(conversationId);

        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler).assemble(captor.capture());
        assertThat(captor.getValue().getConversationSystemPrompt()).isEqualTo("Profile default prompt.");
    }

    @Test
    void marksOversizedTextAttachmentsForReadFallback() {
        UUID conversationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(projectId);
        conv.setAgentId(agentId);

        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");

        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);
        instance.setStatus(Agent.Status.IDLE);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "use file");
        user.setId(userMessageId);

        ConversationMessageAttachment attachment = new ConversationMessageAttachment();
        attachment.setId(attachmentId);
        attachment.setConversationId(conversationId);
        attachment.setMessageId(userMessageId);
        attachment.setFilename("large.txt");
        attachment.setMediaType("text/plain");
        attachment.setSizeBytes(128L);
        attachment.setSha256("abc123");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(agentService.reserveInstance(eq(instanceId), any(), any()))
                .thenReturn(true);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(attachment));
        when(attachmentService.download(conversationId, attachmentId))
                .thenReturn("this text is intentionally too large".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong())).thenReturn(1L);

        boolean dispatched = dispatcher.dispatch(conversationId);
        assertThat(dispatched).isTrue();

        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler).assemble(captor.capture());

        InferenceRequestSpec spec = captor.getValue();
        assertThat(spec.getAttachments()).hasSize(1);
        InferenceRequestSpec.AttachmentDescriptor descriptor = spec.getAttachments().get(0);
        assertThat(descriptor.id()).isEqualTo(attachmentId.toString());
        assertThat(descriptor.inlineText()).isNull();
        assertThat(descriptor.readContentPath())
                .isEqualTo("/api/v1/agent/conversations/" + conversationId
                        + "/attachments/" + attachmentId + "/content");
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

        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(profileId)).thenReturn(Optional.of(new AgentProfile()));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(false);

        boolean dispatched = dispatcher.dispatch(conversationId);

        assertThat(dispatched).isFalse();
        verify(pendingTurnRegistry, never()).enqueue(any(), any());
        // #86 \u2014 the turn isn't silently dropped: a one-time no-agent notice
        // is emitted so the user knows their message is queued.
        verify(conversationNoticeService).emitNoAgentNotice(conversationId);
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
        verify(pendingTurnRegistry, never()).enqueue(any(), any());
    }

    @Test
    void foldsCoveredMessagesIntoLatestContextSummary() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();

        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(UUID.randomUUID());
        conv.setAgentId(agentId);

        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);

        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");

        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);
        instance.setStatus(Agent.Status.IDLE);

        // Three early turns (seq 0..2) get folded into a summary at seq 3 that
        // declares it covers everything up to seq 2; two fresh turns follow.
        ConversationMessage user0 = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "old one");
        ConversationMessage asst1 = newMessage(conversationId, 1L, ConversationMessage.Role.ASSISTANT, "old reply");
        ConversationMessage user2 = newMessage(conversationId, 2L, ConversationMessage.Role.USER, "old two");
        ConversationMessage summary3 = newMessage(conversationId, 3L,
                ConversationMessage.Role.CONTEXT_SUMMARY, "They discussed onboarding.");
        summary3.setPayloadJson("{\"kind\":\"CONTEXT_SUMMARY\",\"coversUpToSequenceNo\":2,\"summarizedMessageCount\":3}");
        ConversationMessage asst4 = newMessage(conversationId, 4L, ConversationMessage.Role.ASSISTANT, "fresh reply");
        ConversationMessage user5 = newMessage(conversationId, 5L, ConversationMessage.Role.USER, "fresh question");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(profileId)).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(agentService.reserveInstance(eq(instanceId), any(), any()))
                .thenReturn(true);
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(user0, asst1, user2, summary3, asst4, user5));

        boolean dispatched = dispatcher.dispatch(conversationId);
        assertThat(dispatched).isTrue();

        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler).assemble(captor.capture());
        List<InferenceRequestSpec.HistoryEntry> history = captor.getValue().getHistory();

        // Window = [summary(@3), asst(@4), user(@5)] — the folded turns 0..2 are gone.
        assertThat(history).hasSize(3);
        // The summary anchors the front, shipped as a SYSTEM entry with the label.
        assertThat(history.get(0).role()).isEqualTo("SYSTEM");
        assertThat(history.get(0).content())
                .startsWith("[Summary of earlier conversation]")
                .contains("They discussed onboarding.");
        assertThat(history.get(1).content()).isEqualTo("fresh reply");
        assertThat(history.get(2).role()).isEqualTo("USER");
        assertThat(history.get(2).content()).isEqualTo("fresh question");
    }

    // ===================== #103 Slice A — native image PARTS ====================

    @Test
    void imageAttachmentWithVisionModelGetsImagePartFlagAndReadPath() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        Conversation conv = newConversation(conversationId, agentId);
        AgentHost agent = newAgentHost(agentId, profileId);
        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        profile.setDefaultModel("gpt-4o");
        Agent instance = newIdleInstance(instanceId, agentId);

        // The resolved model supports vision \u2192 the engine flags the image part.
        Model visionModel = new Model();
        visionModel.setSupportsVision(true);
        when(modelService.findByCode("gpt-4o")).thenReturn(visionModel);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "look");
        user.setId(userMessageId);
        ConversationMessageAttachment image =
                newAttachment(conversationId, userMessageId, attachmentId, "shot.png", "image/png");

        wireReady(conv, agent, profile, instance, instanceId, agentId, conversationId);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(image));

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        InferenceRequestSpec.AttachmentDescriptor d = captureSingleAttachment(conversationId);
        assertThat(d.image()).isTrue();
        assertThat(d.inlineText()).isNull();
        assertThat(d.readContentPath())
                .isEqualTo("/api/v1/agent/conversations/" + conversationId
                        + "/attachments/" + attachmentId + "/content");
    }

    @Test
    void imageAttachmentWithoutVisionModelIsNotFlagged() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        Conversation conv = newConversation(conversationId, agentId);
        AgentHost agent = newAgentHost(agentId, profileId);
        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        profile.setDefaultModel("text-only");
        Agent instance = newIdleInstance(instanceId, agentId);

        // Non-vision model \u2192 image is conveyed as metadata only, never flagged.
        Model textModel = new Model();
        textModel.setSupportsVision(false);
        when(modelService.findByCode("text-only")).thenReturn(textModel);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "look");
        user.setId(userMessageId);
        ConversationMessageAttachment image =
                newAttachment(conversationId, userMessageId, attachmentId, "shot.png", "image/png");

        wireReady(conv, agent, profile, instance, instanceId, agentId, conversationId);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(image));

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        InferenceRequestSpec.AttachmentDescriptor d = captureSingleAttachment(conversationId);
        assertThat(d.image()).isFalse();
        assertThat(d.inlineText()).isNull();
    }

    // ============== #103 Slice B — inline-ratio threshold policy ===============

    @Test
    void demotesAttachmentsThatBreachAggregateInlineBudget() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID att1 = UUID.randomUUID();
        UUID att2 = UUID.randomUUID();

        Conversation conv = newConversation(conversationId, agentId);
        AgentHost agent = newAgentHost(agentId, profileId);
        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        Agent instance = newIdleInstance(instanceId, agentId);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "use files");
        user.setId(userMessageId);
        ConversationMessageAttachment file1 =
                newAttachment(conversationId, userMessageId, att1, "a.txt", "text/plain");
        ConversationMessageAttachment file2 =
                newAttachment(conversationId, userMessageId, att2, "b.txt", "text/plain");

        wireReady(conv, agent, profile, instance, instanceId, agentId, conversationId);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(file1, file2));

        // Each file is 120 bytes \u2192 ~31 estimated tokens; well under the
        // per-attachment cap (1000) so both clear the first gate.
        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong())).thenReturn(1000L);
        when(attachmentService.download(conversationId, att1)).thenReturn(bytesOfLength(120));
        when(attachmentService.download(conversationId, att2)).thenReturn(bytesOfLength(120));
        // Budget 100 tokens, ratio 0.5 \u2192 aggregate inline budget = 50 tokens.
        when(systemSettingService.getInt(eq("context_token_budget"), anyLong())).thenReturn(100L);
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble())).thenReturn(0.5);

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        List<InferenceRequestSpec.AttachmentDescriptor> ds = captureAttachments(conversationId);
        assertThat(ds).hasSize(2);
        // First (upload order) is inlined within budget.
        assertThat(ds.get(0).inlineText()).isNotNull();
        // Second breaches the aggregate budget → demoted to read-on-demand.
        assertThat(ds.get(1).inlineText()).isNull();
        assertThat(ds.get(1).readContentPath())
                .isEqualTo("/api/v1/agent/conversations/" + conversationId
                        + "/attachments/" + att2 + "/content");
    }

    @Test
    void inlinesAtExactRatioBudgetAndDemotesOneTokenOver() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID att1 = UUID.randomUUID();
        UUID att2 = UUID.randomUUID();

        Conversation conv = newConversation(conversationId, agentId);
        AgentHost agent = newAgentHost(agentId, profileId);
        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        Agent instance = newIdleInstance(instanceId, agentId);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "use files");
        user.setId(userMessageId);
        ConversationMessageAttachment file1 =
                newAttachment(conversationId, userMessageId, att1, "a.txt", "text/plain");
        ConversationMessageAttachment file2 =
                newAttachment(conversationId, userMessageId, att2, "b.txt", "text/plain");

        wireReady(conv, agent, profile, instance, instanceId, agentId, conversationId);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(file1, file2));

        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong())).thenReturn(1000L);
        // 196 bytes \u2192 (196/4)+1 = 50 estimated tokens, exactly the aggregate
        // budget (100 \u00d7 0.5); the 4-byte file adds (4/4)+1 = 2 tokens, one+ over.
        when(attachmentService.download(conversationId, att1)).thenReturn(bytesOfLength(196));
        when(attachmentService.download(conversationId, att2)).thenReturn(bytesOfLength(4));
        when(systemSettingService.getInt(eq("context_token_budget"), anyLong())).thenReturn(100L);
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble())).thenReturn(0.5);

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        List<InferenceRequestSpec.AttachmentDescriptor> ds = captureAttachments(conversationId);
        assertThat(ds).hasSize(2);
        // Exactly at the ratio budget → inlined.
        assertThat(ds.get(0).inlineText()).isNotNull();
        // One token over → demoted.
        assertThat(ds.get(1).inlineText()).isNull();
    }

    @Test
    void overSizeCapKeepsSizeFlagNotBudgetFlag() {
        UUID conversationId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        Conversation conv = newConversation(conversationId, agentId);
        AgentHost agent = newAgentHost(agentId, profileId);
        AgentProfile profile = new AgentProfile();
        profile.setSystemPrompt("Profile default prompt.");
        Agent instance = newIdleInstance(instanceId, agentId);

        ConversationMessage user = newMessage(conversationId, 0L, ConversationMessage.Role.USER, "use file");
        user.setId(userMessageId);
        ConversationMessageAttachment file =
                newAttachment(conversationId, userMessageId, attachmentId, "big.txt", "text/plain");

        wireReady(conv, agent, profile, instance, instanceId, agentId, conversationId);
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(userMessageId)).thenReturn(List.of(file));

        // Over the per-attachment SIZE cap (1 token) \u2192 omittedBySize wins; the
        // budget gate never runs for it, so the budget flag stays false.
        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong())).thenReturn(1L);
        when(attachmentService.download(conversationId, attachmentId)).thenReturn(bytesOfLength(120));
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble())).thenReturn(0.5);

        assertThat(dispatcher.dispatch(conversationId)).isTrue();

        InferenceRequestSpec.AttachmentDescriptor d = captureSingleAttachment(conversationId);
        assertThat(d.inlineText()).isNull();
    }

    // ----- shared fixtures for the #103 attachment tests -----

    private static Conversation newConversation(UUID conversationId, UUID agentId) {
        Conversation conv = new Conversation();
        conv.setId(conversationId);
        conv.setProjectId(UUID.randomUUID());
        conv.setAgentId(agentId);
        return conv;
    }

    private static AgentHost newAgentHost(UUID agentId, UUID profileId) {
        AgentHost agent = new AgentHost();
        agent.setId(agentId);
        agent.setProfileId(profileId);
        return agent;
    }

    private static Agent newIdleInstance(UUID instanceId, UUID agentId) {
        Agent instance = new Agent();
        instance.setId(instanceId);
        instance.setAgentHostId(agentId);
        instance.setStatus(Agent.Status.IDLE);
        return instance;
    }

    private static ConversationMessageAttachment newAttachment(
            UUID conversationId, UUID userMessageId, UUID attachmentId, String filename, String mediaType) {
        ConversationMessageAttachment attachment = new ConversationMessageAttachment();
        attachment.setId(attachmentId);
        attachment.setConversationId(conversationId);
        attachment.setMessageId(userMessageId);
        attachment.setFilename(filename);
        attachment.setMediaType(mediaType);
        attachment.setSizeBytes(64L);
        attachment.setSha256("sha-" + attachmentId);
        return attachment;
    }

    private static byte[] bytesOfLength(int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) 'x');
        return bytes;
    }

    private void wireReady(Conversation conv, AgentHost agent, AgentProfile profile, Agent instance,
                           UUID instanceId, UUID agentId, UUID conversationId) {
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByIdWithTools(agent.getProfileId())).thenReturn(Optional.of(profile));
        when(agentInstanceRepository.findByAgentHostIdAndStatus(agentId, Agent.Status.IDLE))
                .thenReturn(List.of(instance));
        when(connectionManager.isAgentIdle(instanceId)).thenReturn(true);
        when(agentService.reserveInstance(eq(instanceId), any(), any())).thenReturn(true);
    }

    private InferenceRequestSpec.AttachmentDescriptor captureSingleAttachment(UUID conversationId) {
        List<InferenceRequestSpec.AttachmentDescriptor> ds = captureAttachments(conversationId);
        assertThat(ds).hasSize(1);
        return ds.get(0);
    }

    private List<InferenceRequestSpec.AttachmentDescriptor> captureAttachments(UUID conversationId) {
        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler).assemble(captor.capture());
        return captor.getValue().getAttachments();
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
