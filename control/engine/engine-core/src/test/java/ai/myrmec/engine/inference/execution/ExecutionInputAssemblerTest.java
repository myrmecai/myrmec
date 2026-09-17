// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.attachment.AttachmentService;
import ai.myrmec.engine.attachment.ConversationMessageAttachment;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationNoticeService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.inference.InferenceRequestAssembler;
import ai.myrmec.engine.inference.InferenceRequestSpec;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.setting.SystemSettingService;
import ai.myrmec.engine.inference.InferenceMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transcript-assembly contract for {@link ExecutionInputAssembler} â€” the
 * unified path's owner of the logic the legacy {@code ConversationTurnDispatcher}
 * used to run inline.
 *
 * <p>Plan 6 Task 4 deleted the dispatcher's legacy assembly branches; this test
 * re-points the behaviour they pinned at the new owner: the #103 attachment
 * clamping chain (per-attachment size cap, aggregate ratio budget, vision
 * gating) and the #8 context-summary fold in the sliding window. Without it the
 * transplant would be untested.</p>
 */
class ExecutionInputAssemblerTest {

    private ConversationRepository conversationRepository;
    private ConversationService conversationService;
    private ai.myrmec.engine.agent.AgentHostRepository agentHostRepository;
    private AgentProfileVersionService agentProfileVersionService;
    private ai.myrmec.engine.agent.AgentRepository agentInstanceRepository;
    private AttachmentService attachmentService;
    private SystemSettingService systemSettingService;
    private ConversationNoticeService conversationNoticeService;
    private SessionContextAssembler sessionContextAssembler;
    private InferenceRequestAssembler inferenceRequestAssembler;
    private GovernancePolicyResolver governancePolicyResolver;
    private ModelService modelService;

    private AgentProfileRepository agentProfileRepository;

    /** Minimal governance definition backing the effective-policy fixture. */
    private ai.myrmec.engine.governance.GovernanceProfileDefinition govDefinition;

    private ExecutionInputAssembler assembler;

    private UUID conversationId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        conversationService = mock(ConversationService.class);
        agentHostRepository = mock(ai.myrmec.engine.agent.AgentHostRepository.class);
        agentProfileVersionService = mock(AgentProfileVersionService.class);
        agentInstanceRepository = mock(ai.myrmec.engine.agent.AgentRepository.class);
        attachmentService = mock(AttachmentService.class);
        systemSettingService = mock(SystemSettingService.class);
        conversationNoticeService = mock(ConversationNoticeService.class);
        sessionContextAssembler = mock(SessionContextAssembler.class);
        inferenceRequestAssembler = mock(InferenceRequestAssembler.class);
        governancePolicyResolver = mock(GovernancePolicyResolver.class);
        modelService = mock(ModelService.class);
        agentProfileRepository = mock(AgentProfileRepository.class);
        govDefinition = mock(ai.myrmec.engine.governance.GovernanceProfileDefinition.class);
        org.mockito.Mockito.lenient().when(govDefinition.code()).thenReturn("test-policy");
        org.mockito.Mockito.lenient().when(govDefinition.featureValues()).thenReturn(Map.of());
        // Materialise the policy OUTSIDE any stubbing chain: constructing it
        // reads through the definition mock, and Mockito rejects a nested mock
        // interaction while a stubbing is still in progress.
        ai.myrmec.engine.governance.EffectivePolicy orgDefault =
                new ai.myrmec.engine.governance.EffectivePolicy(govDefinition);
        when(governancePolicyResolver.resolveOrgDefault()).thenReturn(orgDefault);

        assembler = new ExecutionInputAssembler(
                conversationRepository, conversationService, agentHostRepository,
                agentProfileVersionService, agentInstanceRepository, attachmentService,
                systemSettingService, conversationNoticeService, sessionContextAssembler,
                inferenceRequestAssembler, governancePolicyResolver, modelService,
                mock(ai.myrmec.engine.knowledge.TaskContextResolver.class),
                mock(ai.myrmec.engine.workflow.WorkflowTaskRepository.class));

        conversationId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        // By default there are no attachments; individual tests override.
        when(attachmentService.listForMessage(any())).thenReturn(List.of());
        // The assembler passes the legacy assembler's transcript through
        // untouched; the tests below read the SPEC (its input), which is where
        // the clamping decisions are made.
        when(inferenceRequestAssembler.assemble(any())).thenAnswer(inv -> {
            InferenceRequestSpec spec = inv.getArgument(0);
            return new ai.myrmec.engine.inference.InferenceRequestAssembler.AssembledInference(
                    List.of(), spec.getActiveToolNames() != null
                            ? spec.getActiveToolNames() : List.of());
        });
    }

    // ---------------------------------------------------------- attachments

    @Test
    void oversizedTextAttachmentIsNotInlinedButCarriesTheReadPath() {
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "use file");
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(attachmentId, messageId, "large.txt", "text/plain")));
        // The per-attachment size cap (1 token) is breached by the 32-byte body.
        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong()))
                .thenReturn(1L);
        when(attachmentService.download(conversationId, attachmentId))
                .thenReturn(bytesOfLength(32));

        assemble();

        var descriptor = captureSpec().getAttachments().get(0);
        assertThat(descriptor.id()).isEqualTo(attachmentId.toString());
        assertThat(descriptor.inlineText()).isNull();
        assertThat(descriptor.readContentPath())
                .isEqualTo("/api/v1/agent/conversations/" + conversationId
                        + "/attachments/" + attachmentId + "/content");
    }

    @Test
    void imageIsFlaggedForNativePartsOnlyWhenTheModelSupportsVision() {
        UUID messageId = UUID.randomUUID();
        UUID visionAttachmentId = UUID.randomUUID();

        // Vision model â†’ flagged.
        wireConversation("Profile prompt.", "gpt-4o");
        Model vision = new Model();
        vision.setSupportsVision(true);
        when(modelService.findByCode("gpt-4o")).thenReturn(vision);
        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "look");
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(visionAttachmentId, messageId, "shot.png", "image/png")));

        assemble();

        var flagged = captureSpec().getAttachments().get(0);
        assertThat(flagged.image()).isTrue();
        assertThat(flagged.inlineText()).isNull();
        assertThat(flagged.readContentPath()).contains(visionAttachmentId.toString());

        // Non-vision model â†’ metadata only, never flagged.
        UUID textAttachmentId = UUID.randomUUID();
        wireConversation("Profile prompt.", "text-only");
        Model textOnly = new Model();
        textOnly.setSupportsVision(false);
        when(modelService.findByCode("text-only")).thenReturn(textOnly);
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(textAttachmentId, messageId, "shot.png", "image/png")));

        assemble();

        var unflagged = captureSpec().getAttachments().get(0);
        assertThat(unflagged.image()).isFalse();
        assertThat(unflagged.inlineText()).isNull();
    }

    @Test
    void secondAttachmentIsDemotedWhenItBreachesTheAggregateInlineBudget() {
        UUID messageId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "use files");
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(firstId, messageId, "a.txt", "text/plain"),
                attachment(secondId, messageId, "b.txt", "text/plain")));

        // Both clear the per-attachment gate (1000 tokens each)â€¦
        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong()))
                .thenReturn(1000L);
        when(attachmentService.download(conversationId, firstId)).thenReturn(bytesOfLength(120));
        when(attachmentService.download(conversationId, secondId)).thenReturn(bytesOfLength(120));
        // â€¦but 120 bytes â‰ˆ 31 tokens each against a 100 Ã— 0.5 = 50-token aggregate
        // budget: the first fits, the second is demoted to read-on-demand.
        when(systemSettingService.getInt(eq("context_token_budget"), anyLong())).thenReturn(100L);
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble()))
                .thenReturn(0.5);

        assemble();

        List<ai.myrmec.engine.inference.InferenceRequestSpec.AttachmentDescriptor> descriptors =
                captureSpec().getAttachments();
        assertThat(descriptors).hasSize(2);
        assertThat(descriptors.get(0).inlineText()).isNotNull();
        assertThat(descriptors.get(1).inlineText()).isNull();
        assertThat(descriptors.get(1).readContentPath()).contains(secondId.toString());
    }

    @Test
    void inlineBudgetIsInclusiveAtTheExactRatioAndExclusiveOneTokenOver() {
        UUID messageId = UUID.randomUUID();
        UUID atBudgetId = UUID.randomUUID();
        UUID overBudgetId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "use files");
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(atBudgetId, messageId, "at.txt", "text/plain"),
                attachment(overBudgetId, messageId, "over.txt", "text/plain")));

        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong()))
                .thenReturn(1000L);
        // 196 bytes â†’ (196/4)+1 = 50 estimated tokens = exactly the 50-token
        // budget â†’ inlined. The 4-byte file adds 2 more â†’ one over â†’ demoted.
        when(attachmentService.download(conversationId, atBudgetId)).thenReturn(bytesOfLength(196));
        when(attachmentService.download(conversationId, overBudgetId)).thenReturn(bytesOfLength(4));
        when(systemSettingService.getInt(eq("context_token_budget"), anyLong())).thenReturn(100L);
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble()))
                .thenReturn(0.5);

        assemble();

        List<ai.myrmec.engine.inference.InferenceRequestSpec.AttachmentDescriptor> descriptors =
                captureSpec().getAttachments();
        assertThat(descriptors.get(0).inlineText()).isNotNull();
        assertThat(descriptors.get(1).inlineText()).isNull();
    }

    @Test
    void anOutOfRangeRatioFallsBackToTheDefaultRatherThanDemotingEverything() {
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "use file");
        when(conversationService.listMessages(conversationId)).thenReturn(List.of(user));
        when(attachmentService.listForMessage(messageId)).thenReturn(List.of(
                attachment(attachmentId, messageId, "a.txt", "text/plain")));

        when(systemSettingService.getInt(eq("attachment_inline_token_limit"), anyLong()))
                .thenReturn(1000L);
        when(attachmentService.download(conversationId, attachmentId)).thenReturn(bytesOfLength(40));
        when(systemSettingService.getInt(eq("context_token_budget"), anyLong())).thenReturn(8000L);
        // A corrupt stored ratio must not make the aggregate budget zero.
        when(systemSettingService.getRatio(eq("attachment_inline_ratio_max"), anyDouble()))
                .thenReturn(0.0);

        assemble();

        assertThat(captureSpec().getAttachments().get(0).inlineText()).isNotNull();
    }

    // ------------------------------------------------- context summary fold

    @Test
    void contextSummaryReplacesTheTurnsItFoldedInsideTheSlidingWindow() {
        UUID messageId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user0 = message(UUID.randomUUID(), 0L,
                ConversationMessage.Role.USER, "old one");
        ConversationMessage asst1 = message(UUID.randomUUID(), 1L,
                ConversationMessage.Role.ASSISTANT, "old reply");
        ConversationMessage user2 = message(UUID.randomUUID(), 2L,
                ConversationMessage.Role.USER, "old two");
        ConversationMessage summary3 = message(UUID.randomUUID(), 3L,
                ConversationMessage.Role.CONTEXT_SUMMARY, "They discussed onboarding.");
        summary3.setPayloadJson("{\"kind\":\"CONTEXT_SUMMARY\","
                + "\"coversUpToSequenceNo\":2,\"summarizedMessageCount\":3}");
        ConversationMessage asst4 = message(UUID.randomUUID(), 4L,
                ConversationMessage.Role.ASSISTANT, "fresh reply");
        ConversationMessage user5 = message(messageId, 5L,
                ConversationMessage.Role.USER, "fresh question");
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(user0, asst1, user2, summary3, asst4, user5));

        assemble();

        List<InferenceRequestSpec.HistoryEntry> history = captureSpec().getHistory();
        // Window = [summary(@3), asst(@4), user(@5)] â€” the folded turns 0..2 are gone.
        assertThat(history).hasSize(3);
        assertThat(history.get(0).role()).isEqualTo("SYSTEM");
        assertThat(history.get(0).content())
                .startsWith("[Summary of earlier conversation]")
                .contains("They discussed onboarding.");
        assertThat(history.get(1).content()).isEqualTo("fresh reply");
        assertThat(history.get(2).role()).isEqualTo("USER");
        assertThat(history.get(2).content()).isEqualTo("fresh question");
        // The newest USER turn is the one the spec carries as the prompt.
        assertThat(captureSpec().getUserMessage()).isEqualTo("fresh question");
    }

    @Test
    void noAgentNoticesAreNeverShippedToTheAgent() {
        UUID messageId = UUID.randomUUID();
        wireConversation("Profile prompt.", null);

        ConversationMessage user = message(messageId, 0L,
                ConversationMessage.Role.USER, "hello");
        ConversationMessage notice = message(UUID.randomUUID(), 1L,
                ConversationMessage.Role.SYSTEM, "No agent is currently online.");
        notice.setPayloadJson("{\"kind\":\"NO_AGENT_NOTICE\"}");
        when(conversationService.listMessages(conversationId))
                .thenReturn(List.of(user, notice));
        when(conversationNoticeService.isNoAgentNotice(notice)).thenReturn(true);
        when(conversationNoticeService.isNoAgentNotice(user)).thenReturn(false);

        assemble();

        assertThat(captureSpec().getHistory())
                .noneSatisfy(entry -> assertThat(entry.content())
                        .contains("No agent is currently online."));
    }

    // ------------------------------------------------------------ fixtures

    /** A pinned conversation whose profile version carries {@code defaultModel}. */
    private void wireConversation(String systemPrompt, String defaultModel) {
        UUID profileVersionId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setProjectId(projectId);
        conversation.setAgentProfileVersionId(profileVersionId);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        AgentProfileVersion version = new AgentProfileVersion();
        version.setId(profileVersionId);
        version.setProfileId(profileId);
        version.setSystemPrompt(systemPrompt);
        version.setDefaultModel(defaultModel);
        version.setStatus(AgentProfileVersion.Status.PUBLISHED);
        version.setTools(java.util.Set.of());
        when(agentProfileVersionService.findByIdWithTools(profileVersionId))
                .thenReturn(Optional.of(version));

        AgentProfile profile = new AgentProfile();
        profile.setId(profileId);
        when(agentProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));

        when(sessionContextAssembler.assemble(any(), any(), any(), any()))
                .thenReturn(new ai.myrmec.engine.websocket.message.payload.SessionOpenPayload(
                        null, "CONVERSATION", projectId, null, null, null,
                        List.of(), List.of(), false, null, null, null, null, List.of(), null,
                        null, null));
    }

    private void assemble() {
        // Only exercised for the side effects captured through the spec.
        assembler.assembleConversationInput(conversationId, UUID.randomUUID(), projectId, 1L);
    }

    private InferenceRequestSpec captureSpec() {
        ArgumentCaptor<InferenceRequestSpec> captor =
                ArgumentCaptor.forClass(InferenceRequestSpec.class);
        verify(inferenceRequestAssembler, org.mockito.Mockito.atLeastOnce()).assemble(captor.capture());
        return captor.getValue();
    }

    private static ConversationMessage message(UUID id, long sequenceNo,
                                               ConversationMessage.Role role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id);
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private ConversationMessageAttachment attachment(UUID id, UUID messageId,
                                                     String filename, String mediaType) {
        ConversationMessageAttachment attachment = new ConversationMessageAttachment();
        attachment.setId(id);
        attachment.setConversationId(conversationId);
        attachment.setMessageId(messageId);
        attachment.setFilename(filename);
        attachment.setMediaType(mediaType);
        attachment.setSizeBytes(64L);
        attachment.setSha256("sha-" + id);
        return attachment;
    }

    private static byte[] bytesOfLength(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'x');
        return bytes;
    }
}
