package ai.myrmec.engine.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only message log row. {@link #sequenceNo} is unique per
 * conversation and is assigned by {@code ConversationMessageService} (NOT
 * by the database) so the engine can fan-out the same sequence number
 * to all viewers without a round-trip.
 *
 * <p>Roles map directly to the agent WebSocket protocol:</p>
 * <ul>
 *   <li>{@code USER} — text from a human participant.</li>
 *   <li>{@code ASSISTANT} — completed agent turn (full text, post-stream).</li>
 *   <li>{@code SYSTEM} — engine-injected (system prompt, pinned facts, redactions).</li>
 *   <li>{@code TOOL} — tool call result payload, referenced by {@link #toolCallId}.</li>
 *   <li>{@code APPROVAL_REQUEST} / {@code APPROVAL_RESPONSE} — HITL turns (Phase 7).</li>
 *   <li>{@code CONTEXT_SUMMARY} — engine-generated compaction of older turns (#8).
 *       Folded into the context window in place of the messages it covers; the
 *       originals are retained un-superseded so the full transcript stays intact.</li>
 * </ul>
 */
@Entity
@Table(name = "conversation_messages")
@Getter
@Setter
@NoArgsConstructor
public class ConversationMessage {

    public enum Role { USER, ASSISTANT, SYSTEM, TOOL, APPROVAL_REQUEST, APPROVAL_RESPONSE, CONTEXT_SUMMARY }

    /**
     * Lifecycle of an HITL approval row. Set only on rows whose
     * {@link Role} is {@link Role#APPROVAL_REQUEST}.
     */
    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED }

    /** Participant feedback verdict on an ASSISTANT message (#104a). */
    public enum Rating { UP, DOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    /** Set when role = USER; null otherwise. */
    @Column(name = "author_user_id")
    private UUID authorUserId;

    /** Set when role = ASSISTANT or TOOL; null otherwise. */
    @Column(name = "author_agent_id")
    private UUID authorAgentId;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** Informational — actual model usage rolls up via task_metrics. */
    @Column(name = "model_code", length = 50)
    private String modelCode;

    @Column(name = "token_count")
    private Integer tokenCount;

    /** For TOOL rows: stable id supplied by the model / agent SDK. */
    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    /** Optional JSON payload for TOOL / APPROVAL_* rows. */
    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    /** For branched / regenerated turns. */
    @Column(name = "parent_message_id")
    private UUID parentMessageId;

    /**
     * Set ONLY on {@link Role#APPROVAL_REQUEST} rows. Drives the HITL
     * card state machine. Null on every other role.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    private ApprovalStatus approvalStatus;

    /**
     * The user pinned to decide this approval, if any. Null until the
     * policy engine assigns one (Phase 7e) — currently any project
     * editor may decide.
     */
    @Column(name = "approver_id")
    private UUID approverId;

    /** Hard wall-clock cap; the row flips to EXPIRED past this point. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Participant pin flag (UI affordance). Lets a user mark salient turns
     * and filter the transcript to "pinned only". Does not affect context
     * assembly — {@code Conversation.pinnedFacts} is the engine mechanism
     * for that. Defaults to false.
     */
    @Column(name = "pinned", nullable = false)
    private boolean pinned = false;

    /**
     * Participant thumbs-up / thumbs-down on an ASSISTANT message (#104a).
     * Null until rated; cleared back to null when feedback is withdrawn.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_rating", length = 10)
    private Rating feedbackRating;

    /** Optional free-text note accompanying {@link #feedbackRating}. */
    @Column(name = "feedback_reason")
    private String feedbackReason;

    /** The user who set the current feedback. Null when un-rated. */
    @Column(name = "feedback_by")
    private UUID feedbackBy;

    /** When the current feedback was set. Null when un-rated. */
    @Column(name = "feedback_at")
    private Instant feedbackAt;

    /**
     * Soft-supersede flag (#104b). Set true when a turn is replaced by an
     * edit-resend or a regenerate: the row is retained for transparency
     * (the superseded branch is never destroyed) but is excluded from the
     * active branch the engine assembles into the next turn's context.
     * Defaults to false.
     */
    @Column(name = "superseded", nullable = false)
    private boolean superseded = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
