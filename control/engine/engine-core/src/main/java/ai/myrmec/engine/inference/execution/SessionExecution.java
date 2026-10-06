// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One engine-owned execution (protocol §11.2): a conversation turn or a
 * complete orchestration-task attempt on an allocated session. Exactly one
 * durable terminal outcome (§11.3.6); terminal dedup is by messageId.
 *
 * <p>§3.1 (plan 2026-10-03-session-interaction): the execution projection
 * carries the control revisions, observed hold state, the serial chat
 * ordering + one pending-interaction pointer (set/cleared under THIS row's
 * pessimistic-write lock), the effective immutable interaction policy +
 * usage snapshots (JSONB), and the public durable stream cursor.</p>
 */
@Entity
@Table(name = "session_executions")
@Getter
@Setter
@NoArgsConstructor
public class SessionExecution {

    /** §11.2 execution state machine. */
    public enum State { STARTING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLING, CANCELLED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "service_type", nullable = false, length = 20)
    private String serviceType;   // "CONVERSATION" or "WORKFLOW" (mirrors sessions.service_type)

    /** Conversation turn correlation (§8.1 requestId); null for orchestration. */
    @Column(name = "request_id")
    private String requestId;

    /** §8.2 accept metadata (resolvedModelId / dispatchId / assignmentDigest). */
    @Column(name = "resolved_model_id")
    private String resolvedModelId;
    @Column(name = "dispatch_id")
    private UUID dispatchId;
    @Column(name = "assignment_digest")
    private String assignmentDigest;

    @Column(name = "state", nullable = false, length = 20)
    private State state;

    /** Strictly-increasing per-conversation-session turn ordinal (§8.1 sequenceNo). */
    @Column(name = "sequence_no")
    private Integer sequenceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", columnDefinition = "jsonb")
    private Map<String, Object> inputPayload;   // the §8.1 frame as sent (audit + Plan 6 recovery)

    /** Terminal dedup (§12.1/§12.3): the messageId of the recorded terminal frame. */
    @Column(name = "terminal_message_id")
    private String terminalMessageId;

    /** The recorded terminal outcome as a §8.5/8.6/8.8 payload-shaped map (audit). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "terminal_payload", columnDefinition = "jsonb")
    private Map<String, Object> terminalPayload;

    // ---- §3.1 execution projection (plan 2026-10-03-session-interaction) ----

    /** §3.1: highest engine-issued HOLD/CONTINUE revision. */
    @Column(name = "control_revision", nullable = false)
    private Long controlRevision;

    /** §3.1: highest SDK-accepted command revision. */
    @Column(name = "accepted_control_revision", nullable = false)
    private Long acceptedControlRevision;

    /** §3.1: highest observed SDK stateSequence (§22.5). */
    @Column(name = "control_state_sequence", nullable = false)
    private Long controlStateSequence;

    /** §3.1: observed RUNNING / HOLD_REQUESTED / HELD. */
    @Column(name = "hold_state", nullable = false, length = 20)
    private String holdState;

    /** §3.1: hold-state observation time. */
    @Column(name = "hold_changed_at")
    private Instant holdChangedAt;

    /** §3.1: observation, NOT the engine timer authority. */
    @Column(name = "idle_resume_at")
    private Instant idleResumeAt;

    /** §3.1: serial user-chat ordering; claimed under this row's lock. */
    @Column(name = "next_interaction_ordinal", nullable = false)
    private Long nextInteractionOrdinal;

    /** §3.1/§3.2: the ONE in-flight chat; set/cleared under this row's lock. */
    @Column(name = "pending_interaction_id")
    private UUID pendingInteractionId;

    /** §3.1: effective immutable session policy snapshot (§22.2). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interaction_policy", columnDefinition = "jsonb")
    private Map<String, Object> interactionPolicy;

    /** §3.1/§3.5: attributed known subtotals + usage status. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interaction_usage", columnDefinition = "jsonb")
    private Map<String, Object> interactionUsage;

    /** §3.1: public durable stream cursor (NOT the wire replay cursor). */
    @Column(name = "stream_sequence", nullable = false)
    private Long streamSequence;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (state == null) {
            state = State.STARTING;
        }
        // §3.1 column defaults must be engine-initialized too: H2 enforces
        // the NOT NULL constraint per statement, so a null insert fails
        // before the DB defaults apply.
        if (controlRevision == null) controlRevision = 0L;
        if (acceptedControlRevision == null) acceptedControlRevision = 0L;
        if (controlStateSequence == null) controlStateSequence = 0L;
        if (holdState == null) holdState = "RUNNING";
        if (nextInteractionOrdinal == null) nextInteractionOrdinal = 1L;
        if (streamSequence == null) streamSequence = 0L;
    }
}
