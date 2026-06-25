package ai.myrmec.engine.websocket.message.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code conversation.turn.assign} (Engine → Agent).
 *
 * <p>Carries everything an agent needs to produce one assistant turn in
 * a conversational session: the system prompt (per-conversation override
 * or profile fallback), per-conversation pinned facts, a sliding window
 * of prior messages, and the just-arrived user message. The
 * {@code assistantSequenceNo} is pre-allocated by the engine so the
 * agent's emitted {@code message.delta} / {@code message.complete}
 * frames carry a stable, viewable sequence number from the very first
 * chunk.</p>
 *
 * <p>Phase 6d introduces this as a parallel wire path to
 * {@link TaskAssignPayload}: chat turns are not workflow tasks and the
 * agent SDK runs a different inner loop for them (LLM-only, optional
 * tool calls, streamed back as {@code message.delta} not
 * {@code task.progress}). Reusing {@link TaskAssignPayload.ModelInfo}
 * is deliberate — the model lookup + decryption logic is identical and
 * splitting the type would force the agent SDK to handle two shapes
 * for the same concept.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurnAssignPayload {

    /** Conversation this turn belongs to. */
    private UUID conversationId;

    /** Parent project (for tool ACL + budget scope). */
    private UUID projectId;

    /** The agent profile the conversation is pinned to. */
    private UUID agentId;

    /**
     * Pre-allocated sequence_no for the assistant message the agent is
     * about to stream. The agent MUST echo this back on every
     * {@code message.delta} and on the final {@code message.complete}
     * so viewers can stitch the streamed turn to the eventually
     * persisted row.
     */
    private long assistantSequenceNo;

    /**
     * Effective system prompt — per-conversation override if set,
     * otherwise the agent profile's default. Already resolved by the
     * engine; the agent should use it verbatim.
     */
    private String systemPrompt;

    /**
     * Per-conversation pinned facts (#9). Free-form text the engine
     * prepends to the model input. Null/empty if nothing pinned.
     */
    private String pinnedFacts;

    /**
     * Sliding context window — most recent N messages, oldest first.
     * The engine handles truncation and (eventually) summarisation; the
     * agent treats this list as the canonical prior context.
     */
    private List<HistoryEntry> history;

    /**
     * The user message that triggered this turn — duplicated from
     * {@link #history} last entry for convenience so the agent doesn't
     * have to scan.
     */
    private String userMessage;

    /** Per-turn timeout in seconds. */
    private int timeoutSeconds;

    /** Model handle + decrypted API key — same shape as {@link TaskAssignPayload}. */
    private TaskAssignPayload.ModelInfo model;

    /**
     * Attachments bound to the triggering user message (#103). Empty/null
     * when the turn carried no files. Quarantined uploads are never
     * conveyed; only scan-clean rows reach the agent.
     */
    private List<AttachmentDescriptor> attachments;

    /**
     * Why this turn is being dispatched (#8). {@code "CHAT"} (default) is a
     * normal user-facing turn whose completion persists an ASSISTANT row;
     * {@code "SUMMARY"} is an engine-orchestrated summarisation turn whose
     * completion is routed into a {@code CONTEXT_SUMMARY} row instead and is
     * never shown as the conversation's answer. The agent SDK ignores this
     * field — it produces a turn identically either way; the distinction is
     * purely engine-side completion routing.
     */
    @Builder.Default
    private String purpose = "CHAT";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryEntry {
        /** USER, ASSISTANT, or SYSTEM. */
        private String role;
        /** Message body. */
        private String content;
        /** Conversation sequence number this entry holds. */
        private long sequenceNo;
    }

    /**
     * A single attachment conveyed to the agent. Text documents small
     * enough to fit the inline budget carry their extracted text in
     * {@link #inlineText}; larger ones (and binaries) carry metadata only
     * and the agent fetches bytes on demand. Image parts are flagged via
     * {@link #image} only when the resolved model supports vision.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentDescriptor {
        /** Stable attachment id (for on-demand fetch). */
        private UUID id;
        /** Original client filename (sanitised). */
        private String filename;
        /** Normalised MIME type. */
        private String mediaType;
        /** Stored size in bytes. */
        private long sizeBytes;
        /** SHA-256 of the stored bytes. */
        private String sha256;
        /**
         * True only when this is an image AND the resolved model supports
         * vision — the agent should send it as a native image part.
         */
        private boolean image;
        /**
         * Extracted text injected inline for small text documents; null
         * when the file is binary or exceeds the inline budget.
         */
        private String inlineText;

        /**
         * True when {@link #inlineText} is null specifically because the
         * extracted text exceeded the inline token budget. False for binaries
         * and extraction/read failures.
         */
        private boolean inlineTextOmittedBySize;

        /**
         * True when {@link #inlineText} is null specifically because inlining
         * this attachment's text would have pushed the turn's aggregate inline
         * budget past {@code attachment_inline_ratio_max × contextBudget}
         * (#103 Slice B). Distinct from {@link #inlineTextOmittedBySize} (a
         * per-attachment size-cap breach) so the agent and tests can tell why
         * the text was withheld; either way the agent reads it on demand via
         * {@link #readContentPath}.
         */
        private boolean inlineTextOmittedByBudget;

        /**
         * Agent-authenticated REST path for fetching raw bytes on demand.
         * Populated for all clean attachments.
         */
        private String readContentPath;
    }
}
