// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Input spec for {@link TranscriptComposer#compose} (§6.2).
 *
 * <p>Carries everything the composer needs to build the transcript.
 * Fields are nullable when not applicable to the service type.</p>
 */
@Getter
@Builder
public class InferenceRequestSpec {

    // ── Common ──
    private final String serviceType;      // "WORKFLOW" or "CONVERSATION"
    private final UUID sessionId;
    private final UUID requestId;
    private final UUID projectId;
    private final UUID assistantVersionId;
    private final long sequenceNo;          // assistant sequence no (conversation) / step index (workflow)
    private final String stepId;           // workflow step id (nullable for conversation turns)
    private final String governanceProfileCode;  // resolved profile code (for manifest audit)
    private final ai.myrmec.engine.context.ContextSnapshot contextSnapshot;  // pinned snapshot (null for IMMEDIATE_EFFECT)
    private final String contextPinning;  // resolved pinning label (PINNED_AT_START or IMMEDIATE_EFFECT)

    // ── Workflow-specific (TaskAssignPayload fields) ──
    private final String systemPrompt;             // profile system prompt
    private final String stepPrompt;              // workflow step prompt
    private final Map<String, Object> input;       // workflow step input (may contain "messages" array or "prompt")
    private final List<KnowledgeEntry> knowledge;  // compiled knowledge entries from TaskContextResolver

    // ── Conversation-specific (ConversationTurnAssignPayload fields) ──
    private final String conversationSystemPrompt;  // override ?? profile system prompt
    private final String pinnedFacts;
    private final List<HistoryEntry> history;        // sliding-window history
    private final String userMessage;               // last user content
    private final List<AttachmentDescriptor> attachments;
    private final List<String> activeToolNames;     // tool codes active for this turn (conversation)

    // ── Orchestration (Feature 10, §16.2) ──
    /** The complete self-contained OrchestrationAssignment; null for ordinary inference. */
    private final Map<String, Object> orchestrationAssignment;
    /** SHA-256 over the canonical assignment bytes; null for ordinary inference. */
    private final String orchestrationAssignmentDigest;

    // ── Knowledge entry (workflow path) ──
    public record KnowledgeEntry(
            String name,
            String content,
            String category) {}

    // ── History entry (conversation path) ──
    public record HistoryEntry(
            String role,     // "user", "assistant", "system"
            String content) {}

    // ── Attachment descriptor (conversation path) ──
    public record AttachmentDescriptor(
            String id,
            String filename,
            String mediaType,
            long sizeBytes,
            String inlineText,   // nullable
            boolean image,       // is image attachment
            String readContentPath) {}  // nullable — for multimodal fetch
}