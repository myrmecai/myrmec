// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * §3.3 control intent + §22.7 proposal row. The row's {@code id} is the
 * SDK proposal ID (host proposal) or the engine-generated direct request
 * ID. Statuses follow §22.7 (ACCEPTED, CONFIRMATION_REQUIRED, REJECTED,
 * DECLINED, EXPIRED) plus PENDING for the awaiting-confirmation window.
 */
@Entity
@Table(name = "execution_control_requests")
@Getter
@Setter
@NoArgsConstructor
public class ExecutionControlRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** session_executions.id — the FK column, loaded manually (no JPA relation). */
    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    /**
     * §3.3: valid only for an admitted pending interaction; nullable for
     * direct requests. FK fk_execution_control_requests_interaction
     * (no cascade — audit rows survive interaction deletion).
     */
    @Column(name = "interaction_id", updatable = false)
    private UUID interactionId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    /** §3.3/§22.4: HOLD | CONTINUE | CANCEL. */
    @Column(name = "action", nullable = false, length = 10, updatable = false)
    private String action;

    /** §3.3: BUTTON (direct request) | CHAT (SDK proposal). */
    @Column(name = "origin", nullable = false, length = 10, updatable = false)
    private String origin;

    /** §3.3 idempotency key member; null for SDK-proposal rows. */
    @Column(name = "client_request_id", updatable = false)
    private UUID clientRequestId;

    /** §3.3 digest over canonical request fields. */
    @Column(name = "request_digest", nullable = false, length = 64, updatable = false)
    private String requestDigest;

    /** §22.7 disposition (see class javadoc); column varchar(30). */
    @Column(name = "status", nullable = false, length = 30)
    private InteractionControlStatus status;

    /** §22.7: engine revisions increase per request disposition. */
    @Column(name = "resolution_revision", nullable = false)
    private Long resolutionRevision;

    /** §22.7: for HOLD/CONTINUE, the issued control revision; null otherwise. */
    @Column(name = "control_revision")
    private Long controlRevision;

    /** §22.7: ACCEPTED carries the persisted command messageId. */
    @Column(name = "command_message_id", length = 255)
    private String commandMessageId;

    /** §22.7: the confirming actor (stored separately from the initiating actor). */
    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    /** §3.3: the awaiting-confirmation window deadline. */
    @Column(name = "confirmation_expires_at")
    private Instant confirmationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** §3.3: explanation after redaction (§15) — null when nothing survives. */
    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    /** §22.7 refusal code (FORBIDDEN, EXECUTION_TERMINAL, INVALID_INTERACTION, …). */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (id == null) {
            // §3.3: wire proposal ID is the row primary key — assigned, not
            // generated. SDK proposal rows arrive with their proposal ID as
            // the PK; engine direct requests mint one here.
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = InteractionControlStatus.PENDING;
        }
    }
}