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
 * </ul>
 */
@Entity
@Table(name = "conversation_messages")
@Getter
@Setter
@NoArgsConstructor
public class ConversationMessage {

    public enum Role { USER, ASSISTANT, SYSTEM, TOOL, APPROVAL_REQUEST, APPROVAL_RESPONSE }

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
