// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

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
 * §3.2 interaction record: one admitted user chat on an orchestration
 * execution — the durable transcript row AND the idempotency key set.
 * The row's {@code id} IS the wire {@code interactionId} (§22.6).
 */
@Entity
@Table(name = "execution_interactions")
@Getter
@Setter
@NoArgsConstructor
public class ExecutionInteraction {

    /** §3.2 interaction state machine. */
    // enum InteractionStatus lives in this package (§3.2 fixed record).

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** session_executions.id — the FK column, loaded manually (no JPA relation). */
    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    /** Serial user-chat ordering; unique (execution_id, ordinal) (§3.2). */
    @Column(name = "ordinal", nullable = false)
    private Long ordinal;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    /** §4 idempotency key (execution, actor, clientRequestId); unique. */
    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    /** §3.2 digest over canonical admitted request fields. */
    @Column(name = "request_digest", nullable = false, length = 64, updatable = false)
    private String requestDigest;

    @Column(name = "status", nullable = false, length = 20)
    private InteractionStatus status;

    @Column(name = "request_text", nullable = false, columnDefinition = "text")
    private String requestText;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    /** §3.2 error detail as a §4 JSON shape; null on success. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error", columnDefinition = "jsonb")
    private Map<String, Object> error;

    /** §3.5 attributed usage subtotals for this interaction. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage", columnDefinition = "jsonb")
    private Map<String, Object> usage;

    /** §3.5 accounting status: KNOWN / UNKNOWN (bounded varchar). */
    @Column(name = "usage_status", nullable = false, length = 10)
    private String usageStatus;

    /** §3.2: admitted interactions settle only until this instant. */
    @Column(name = "response_deadline", nullable = false)
    private Instant responseDeadline;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** §3.2/§3.5: expiry handling owns pre-outcome rows past this instant. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** §3.2/§22.6: the complete/failed frame's messageId (terminal dedup). */
    @Column(name = "terminal_message_id", length = 255)
    private String terminalMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = InteractionStatus.ACCEPTED;
        }
        if (usageStatus == null) {
            usageStatus = "UNKNOWN";
        }
    }
}