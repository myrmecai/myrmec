// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.attachment.AttachmentService;
import ai.myrmec.engine.attachment.ConversationMessageAttachment;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationNoticeService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.inference.InferenceRequestAssembler;
import ai.myrmec.engine.inference.InferenceRequestSpec;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.knowledge.TaskContextResolver;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.setting.SystemSettingService;
import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the §8.1 conversation {@code execution.start} input block from the
 * assembled inference request — the same transcript the legacy
 * inference.assign path ships (§16 mapping), rewrapped into the
 * execution-lifecycle shape. Orchestration sessions carry no input block
 * (the assignment was installed at session.open).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionInputAssembler {

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final AgentHostRepository agentHostRepository;
    private final AgentProfileVersionService agentProfileVersionService;
    private final AgentRepository agentInstanceRepository;
    private final AttachmentService attachmentService;
    private final SystemSettingService systemSettingService;
    private final ConversationNoticeService conversationNoticeService;
    private final SessionContextAssembler sessionContextAssembler;
    private final InferenceRequestAssembler inferenceRequestAssembler;
    private final GovernancePolicyResolver governancePolicyResolver;
    private final ModelService modelService;
    private final TaskContextResolver contextResolver;
    private final WorkflowTaskRepository workflowTaskRepository;

    private static final int HISTORY_LIMIT = 20;

    /** Setting key for the max inline-injected text size, in estimated tokens. */
    private static final String INLINE_TOKEN_LIMIT_KEY = "attachment_inline_token_limit";
    private static final long INLINE_TOKEN_LIMIT_DEFAULT = 4000L;

    /**
     * Setting key for the maximum aggregate fraction of the per-turn context
     * token budget that inline attachment text may occupy across all
     * attachments on the turn (#103 Slice B). Read forgivingly via
     * {@link SystemSettingService#getRatio} and clamped to {@code (0,1]} — a
     * zero/out-of-range value falls back to the default.
     */
    private static final String INLINE_RATIO_MAX_KEY = "attachment_inline_ratio_max";
    private static final double INLINE_RATIO_MAX_DEFAULT = 0.5;

    /** Setting key for the resolved per-turn context token budget. */
    private static final String CONTEXT_TOKEN_BUDGET_KEY = "context_token_budget";
    private static final long CONTEXT_TOKEN_BUDGET_DEFAULT = 8000L;

    /**
     * Assemble the input payload for a conversation turn. Returns the
     * §8.1 wire payload map (messages, attachments, toolPolicy, output)
     * ready for ExecutionStartPayload conversion.
     */
    public java.util.Map<String, Object> assembleConversationInput(UUID conversationId, UUID sessionId,
                                                                    UUID projectId, long sequenceNo) {
        // Build the legacy spec exactly as ConversationTurnDispatcher does
        // (sliding window + system prompt + tools + attachments), then
        // project it into the §8.1 shape. The legacy assembler's output
        // (InferenceAssignPayload) is the single source of transcript truth.
        InferenceRequestSpec spec = buildLegacySpec(conversationId, sessionId, projectId, sequenceNo);
        InferenceAssignPayload assign = inferenceRequestAssembler.assemble(spec);

        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("messages", assign.messages());
        input.put("toolPolicy", java.util.Map.of(
                "activeToolNames", assign.activeToolNames() == null ? java.util.List.of() : assign.activeToolNames(),
                "approvalMode", "ENGINE"));
        input.put("output", java.util.Map.of(
                "stream", true,           // conversation turns always stream
                "responseSequenceNo", sequenceNo,
                "format", "TEXT"));
        // The attachments ride the messages' content parts (legacy shape);
        // §8.1's separate attachments array is additive and left null.
        return input;
    }

    /**
     * Assemble the &sect;8.1 input block for an ordinary workflow step: the same
     * per-step transcript the legacy {@code inference.assign} shipped &mdash; the
     * pinned profile version's system prompt, the step prompt, the task's input
     * block and resolved knowledge &mdash; rewrapped into the execution shape.
     *
     * <p>Model/tool policy is resolved from the pinned published version, never
     * the host: the workflow step's profile binding is the behaviour contract
     * (&sect;3.7).</p>
     */
    public java.util.Map<String, Object> assembleWorkflowInput(
            WorkflowTask task, UUID sessionId, long stepIndex) {
        InferenceRequestSpec spec = buildWorkflowSpec(task, sessionId,
                task.getRequest().getWorkflow().getProject().getId(), stepIndex);
        InferenceAssignPayload assign = inferenceRequestAssembler.assemble(spec);

        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("messages", assign.messages());
        input.put("toolPolicy", java.util.Map.of(
                "activeToolNames", assign.activeToolNames() == null
                        ? java.util.List.of() : assign.activeToolNames(),
                "approvalMode", "ENGINE"));
        input.put("output", java.util.Map.of(
                "stream", false,          // workflow steps are single-shot
                "responseSequenceNo", stepIndex,
                "format", "TEXT"));
        return input;
    }

    private InferenceRequestSpec buildWorkflowSpec(WorkflowTask task, UUID sessionId,
                                                   UUID projectId, long stepIndex) {
        WorkflowRequest request = task.getRequest();
        Workflow workflow = request.getWorkflow();
        AgentProfile profile = task.getAgentProfile();
        // §16.1: the behaviour contract lives on the published version row.
        AgentProfileVersion publishedVersion = agentProfileVersionService
                .findPublished(profile.getId()).orElse(null);

        TaskContext context = contextResolver.resolve(projectId, task.getStepId(), null);
        if (request.getBranch() != null && context.getWorkspace() != null) {
            context.getWorkspace().setBranch(request.getBranch());
        }
        List<InferenceRequestSpec.KnowledgeEntry> knowledge = List.of();
        if (context.getKnowledge() != null) {
            knowledge = context.getKnowledge().stream()
                    .map(k -> new InferenceRequestSpec.KnowledgeEntry(
                            k.getName(), k.getContent(), k.getCategory()))
                    .toList();
        }

        List<String> activeToolNames = publishedVersion == null ? List.of()
                : publishedVersion.getTools().stream()
                        .map(t -> t.getCode() == null ? t.getName() : t.getCode())
                        .toList();

        return InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .sessionId(sessionId)
                .requestId(task.getId())
                .projectId(projectId)
                .sequenceNo(stepIndex)
                .stepId(task.getStepId())
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .systemPrompt(publishedVersion != null ? publishedVersion.getSystemPrompt() : null)
                .stepPrompt(findStepPrompt(workflow, task.getStepId()))
                .input(task.getInput())
                .knowledge(knowledge)
                .activeToolNames(activeToolNames)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String findStepPrompt(Workflow workflow, String stepId) {
        if (workflow.getSteps() == null) {
            return null;
        }
        for (java.util.Map<String, Object> step : workflow.getSteps()) {
            if (stepId.equals(step.get("id"))) {
                Object prompt = step.get("prompt");
                return prompt != null ? prompt.toString() : null;
            }
        }
        return null;
    }

    private InferenceRequestSpec buildLegacySpec(UUID conversationId, UUID sessionId,
                                                 UUID projectId, long sequenceNo) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Conversation not found: " + conversationId));

        UUID pinnedVersionId = conversation.getAgentProfileVersionId();
        if (pinnedVersionId == null) {
            throw new IllegalArgumentException("Conversation has no pinned profile version");
        }
        AgentProfileVersion profileVersion = agentProfileVersionService.findByIdWithTools(pinnedVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pinned agent profile version not found: " + pinnedVersionId));

        // §3.7/§16.1: the pinned version carries the whole behaviour contract
        // (profile id, system prompt, tools). No host lookup — the host is
        // needed at SEND time, not at input-assembly time, and a conversation
        // with a pin but no bound host still assembles valid input.
        // Assemble session.open to extract the active tool names.
        SessionOpenPayload sessionOpen = sessionContextAssembler.assemble(
                "CONVERSATION", conversationId, projectId, profileVersion.getProfileId());

        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        List<ConversationMessage> active = all.stream()
                .filter(m -> !m.isSuperseded())
                // Drop engine no-agent notices: a "no agent online" line
                // must never be shipped to the agent that picks the turn up.
                .filter(m -> !conversationNoticeService.isNoAgentNotice(m))
                .toList();
        List<ConversationTurnAssignPayload.HistoryEntry> historyEntries = buildSlidingWindow(active);
        String userMessage = lastUserContent(active).orElse("");
        long assistantSequenceNo = all.isEmpty()
                ? 0L
                : all.get(all.size() - 1).getSequenceNo() + 1;

        String systemPrompt = conversation.getSystemPromptOverride() != null
                ? conversation.getSystemPromptOverride()
                : (profileVersion != null ? profileVersion.getSystemPrompt() : null);

        List<InferenceRequestSpec.HistoryEntry> history = historyEntries.stream()
                .map(h -> new InferenceRequestSpec.HistoryEntry(h.getRole(), h.getContent()))
                .toList();
        List<InferenceRequestSpec.AttachmentDescriptor> attachments =
                buildAttachments(conversationId, active, profileVersion).stream()
                        .map(a -> new InferenceRequestSpec.AttachmentDescriptor(
                                a.getId().toString(), a.getFilename(), a.getMediaType(), a.getSizeBytes(),
                                a.getInlineText(), a.isImage(), a.getReadContentPath()))
                        .toList();

        List<String> activeToolNames = sessionOpen.tools() != null
                ? sessionOpen.tools().stream()
                        .map(SessionOpenPayload.ToolDefinition::name)
                        .toList()
                : java.util.List.of();

        return InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(sessionId)
                .requestId(conversationId)
                .projectId(projectId)
                .sequenceNo(assistantSequenceNo)
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .contextSnapshot(conversation.getContextSnapshot())
                .contextPinning(conversation.getContextSnapshot() != null ? "PINNED_AT_START" : null)
                .conversationSystemPrompt(systemPrompt)
                .pinnedFacts(conversation.getPinnedFacts())
                .history(history)
                .userMessage(userMessage)
                .attachments(attachments)
                .activeToolNames(activeToolNames)
                .build();
    }

    /**
     * Returns the most-recent {@link #HISTORY_LIMIT} messages, oldest
     * first. Always copies into an {@code ArrayList} so callers (and
     * Jackson) can iterate without surprises.
     *
     * <p>When the active branch carries a {@link
     * ConversationMessage.Role#CONTEXT_SUMMARY}, the messages it folded are
     * dropped and the summary itself anchors the window in their place. The
     * most-recent summary wins; older (superseded) summaries and the raw
     * turns they compacted never reach the agent. The summary ships on the
     * wire as a {@code SYSTEM} entry (the SDK only understands USER /
     * ASSISTANT / SYSTEM) with a short label so the model reads it as prior
     * context; the persisted row keeps its {@code CONTEXT_SUMMARY} role for
     * the transcript's transparency marker (#8a).</p>
     */
    private List<ConversationTurnAssignPayload.HistoryEntry> buildSlidingWindow(
            List<ConversationMessage> all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }

        ConversationMessage latestSummary = null;
        for (ConversationMessage m : all) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY
                    && (latestSummary == null
                        || m.getSequenceNo() > latestSummary.getSequenceNo())) {
                latestSummary = m;
            }
        }

        List<ConversationMessage> effective;
        if (latestSummary == null) {
            effective = all;
        } else {
            long coversUpTo = summaryCoverage(latestSummary);
            effective = new ArrayList<>(all.size());
            effective.add(latestSummary);
            for (ConversationMessage m : all) {
                if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY) {
                    continue; // latest already anchored; drop superseded summaries
                }
                if (m.getSequenceNo() <= coversUpTo) {
                    continue; // folded into the summary
                }
                effective.add(m);
            }
        }

        int from = Math.max(0, effective.size() - HISTORY_LIMIT);
        List<ConversationMessage> windowed =
                new ArrayList<>(effective.subList(from, effective.size()));
        // Always keep the summary anchor at the front, even if a large
        // post-summary tail would otherwise trim it out of the window.
        if (latestSummary != null && !windowed.contains(latestSummary)) {
            windowed.add(0, latestSummary);
        }

        List<ConversationTurnAssignPayload.HistoryEntry> out = new ArrayList<>(windowed.size());
        for (ConversationMessage m : windowed) {
            if (m.getRole() == ConversationMessage.Role.CONTEXT_SUMMARY) {
                out.add(ConversationTurnAssignPayload.HistoryEntry.builder()
                        .role(ConversationMessage.Role.SYSTEM.name())
                        .content(SUMMARY_WIRE_PREFIX
                                + (m.getContent() == null ? "" : m.getContent()))
                        .sequenceNo(m.getSequenceNo())
                        .build());
            } else {
                out.add(ConversationTurnAssignPayload.HistoryEntry.builder()
                        .role(m.getRole().name())
                        .content(m.getContent())
                        .sequenceNo(m.getSequenceNo())
                        .build());
            }
        }
        return out;
    }

    /** Label prefixed to a context summary's body on the wire. */
    private static final String SUMMARY_WIRE_PREFIX = "[Summary of earlier conversation]\n";

    /**
     * The highest sequence number folded into the given summary. Reads the
     * authoritative {@code coversUpToSequenceNo} from the row's marker;
     * falls back to "everything strictly before the summary row" when the
     * marker is absent or unparseable.
     */
    private long summaryCoverage(ConversationMessage summary) {
        String json = summary.getPayloadJson();
        if (json != null && !json.isBlank()) {
            try {
                ai.myrmec.engine.conversation.ContextSummaryMarker marker =
                        new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                                ai.myrmec.engine.conversation.ContextSummaryMarker.class);
                if (marker != null && marker.coversUpToSequenceNo() != null) {
                    return marker.coversUpToSequenceNo();
                }
            } catch (Exception e) {
                log.warn("Unparseable CONTEXT_SUMMARY marker on message {} (conv {}): {}",
                        summary.getId(), summary.getConversationId(), e.getMessage());
            }
        }
        return summary.getSequenceNo() - 1;
    }

    private Optional<String> lastUserContent(List<ConversationMessage> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            ConversationMessage m = all.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                return Optional.ofNullable(m.getContent());
            }
        }
        return Optional.empty();
    }

    /**
     * Build the attachment descriptors for the turn from the clean rows
     * bound to the most-recent active USER message. Small text documents
     * are extracted inline (within the inline budget); images are flagged
     * for native vision parts only when the resolved model supports vision.
     */
    private List<ConversationTurnAssignPayload.AttachmentDescriptor> buildAttachments(
            UUID conversationId, List<ConversationMessage> active, AgentProfileVersion version) {
        UUID userMessageId = null;
        for (int i = active.size() - 1; i >= 0; i--) {
            ConversationMessage m = active.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                userMessageId = m.getId();
                break;
            }
        }
        if (userMessageId == null) {
            return Collections.emptyList();
        }
        List<ConversationMessageAttachment> rows = attachmentService.listForMessage(userMessageId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        boolean supportsVision = resolveSupportsVision(version);
        long inlineTokenLimit = systemSettingService.getInt(
                INLINE_TOKEN_LIMIT_KEY, INLINE_TOKEN_LIMIT_DEFAULT);
        double ratioMax = systemSettingService.getRatio(
                INLINE_RATIO_MAX_KEY, INLINE_RATIO_MAX_DEFAULT);
        if (ratioMax <= 0.0 || ratioMax > 1.0) {
            ratioMax = INLINE_RATIO_MAX_DEFAULT;
        }
        long contextBudget = systemSettingService.getInt(
                CONTEXT_TOKEN_BUDGET_KEY, CONTEXT_TOKEN_BUDGET_DEFAULT);
        long aggregateInlineBudget = (long) Math.floor(ratioMax * contextBudget);
        long runningInlineTokens = 0L;
        List<ConversationTurnAssignPayload.AttachmentDescriptor> out = new ArrayList<>(rows.size());
        for (ConversationMessageAttachment row : rows) {
            boolean isImage = row.getMediaType() != null
                    && row.getMediaType().startsWith("image/");
            InlineExtractionResult inline = InlineExtractionResult.none();
            if (!isImage && isTextLike(row.getMediaType())) {
                inline = extractInlineText(conversationId, row, inlineTokenLimit);
            }

            String inlineText = inline.inlineText();
            boolean omittedBySize = inline.omittedBySize();
            boolean omittedByBudget = false;
            if (inlineText != null) {
                if (runningInlineTokens + inline.estimatedTokens() > aggregateInlineBudget) {
                    inlineText = null;
                    omittedByBudget = true;
                } else {
                    runningInlineTokens += inline.estimatedTokens();
                }
            }

            out.add(ConversationTurnAssignPayload.AttachmentDescriptor.builder()
                    .id(row.getId())
                    .filename(row.getFilename())
                    .mediaType(row.getMediaType())
                    .sizeBytes(row.getSizeBytes())
                    .sha256(row.getSha256())
                    .image(isImage && supportsVision)
                    .inlineText(inlineText)
                    .inlineTextOmittedBySize(omittedBySize)
                    .inlineTextOmittedByBudget(omittedByBudget)
                    .readContentPath(buildAgentAttachmentContentPath(conversationId, row.getId()))
                    .build());
        }
        return out;
    }

    private static String buildAgentAttachmentContentPath(UUID conversationId, UUID attachmentId) {
        return "/api/v1/agent/conversations/" + conversationId
                + "/attachments/" + attachmentId + "/content";
    }

    /** Whether the version's default model is flagged vision-capable. */
    private boolean resolveSupportsVision(AgentProfileVersion version) {
        if (version == null || version.getDefaultModel() == null) {
            return false;
        }
        try {
            return modelService.findByCode(version.getDefaultModel()).isSupportsVision();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTextLike(String mediaType) {
        return mediaType != null
                && (mediaType.startsWith("text/")
                || mediaType.equals("application/json")
                || mediaType.equals("application/xml"));
    }

    /**
     * Decode a text attachment's bytes as UTF-8 and return them when the
     * estimated token count (~chars/4) fits the inline budget; otherwise
     * null so the agent fetches it on demand instead.
     */
    private InlineExtractionResult extractInlineText(
            UUID conversationId,
            ConversationMessageAttachment row,
            long inlineTokenLimit) {
        try {
            byte[] bytes = attachmentService.download(conversationId, row.getId());
            long estimatedTokens = (bytes.length / 4L) + 1L;
            if (estimatedTokens > inlineTokenLimit) {
                return InlineExtractionResult.omittedBySizeLimit();
            }
            return InlineExtractionResult.inline(
                    new String(bytes, StandardCharsets.UTF_8),
                    estimatedTokens);
        } catch (Exception e) {
            log.warn("Could not extract inline text for attachment {} (conv {}): {}",
                    row.getId(), conversationId, e.getMessage());
            return InlineExtractionResult.none();
        }
    }

    private record InlineExtractionResult(String inlineText, boolean omittedBySize, long estimatedTokens) {
        static InlineExtractionResult inline(String inlineText, long estimatedTokens) {
            return new InlineExtractionResult(inlineText, false, estimatedTokens);
        }

        static InlineExtractionResult omittedBySizeLimit() {
            return new InlineExtractionResult(null, true, 0L);
        }

        static InlineExtractionResult none() {
            return new InlineExtractionResult(null, false, 0L);
        }
    }
}
