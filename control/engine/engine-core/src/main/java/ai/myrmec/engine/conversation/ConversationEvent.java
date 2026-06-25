package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Map;
import java.util.UUID;

/**
 * ConversationEvent — one immutable row in the append-only conversation
 * lifecycle log (conversation-observability §4). Maps 1:1 with the
 * {@code conversation_events} table.
 *
 * <p>Each row records a single FSM transition: the {@link #fromState} /
 * {@link #toState} agent runtime status either side of it, the
 * {@link #reasonCode} for why, and per-attempt worker attribution
 * ({@link #agentId} / {@link #agentHostId} / {@link #bindAttemptNo}). The log
 * is the source of truth for funnel metrics and audit, so it is append-only —
 * never updated or deleted — and carries IDs + dimensions only, never message
 * content.</p>
 *
 * <p>{@link #seq} is monotonic per conversation and unique with
 * {@code conversation_id}, so writes are idempotent. Attribution columns are
 * deliberately not foreign keys: the ephemeral {@code agents} row is reaped on
 * release/host-loss, but this log must outlive it as the authoritative
 * per-attempt history.</p>
 */
@Entity
@Table(name = "conversation_events")
@Getter
@Setter
@NoArgsConstructor
public class ConversationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    /** Monotonic per conversation; unique with {@code conversationId}. */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    /** Agent runtime status before the transition; null for the first event. */
    @Column(name = "from_state", length = 20, updatable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 20, updatable = false)
    private String toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private ConversationEventReason reasonCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Which ephemeral worker served this attempt (bare attribution stamp). */
    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    /** Which durable machine it ran on (bare attribution stamp). */
    @Column(name = "agent_host_id", updatable = false)
    private UUID agentHostId;

    /** Ties this event to one connect attempt. */
    @Column(name = "bind_attempt_no", nullable = false, updatable = false)
    private int bindAttemptNo;

    /** Low-cardinality JSON extras (e.g. latency_ms); never message content. */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "attributes", updatable = false)
    private Map<String, Object> attributes;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
