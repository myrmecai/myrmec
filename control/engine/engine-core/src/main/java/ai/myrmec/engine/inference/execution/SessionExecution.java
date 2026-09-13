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

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (state == null) {
            state = State.STARTING;
        }
    }
}
