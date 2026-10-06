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
 * §3.4 persisted command dispatch. The row's {@code id} IS the wire
 * {@code messageId} (§3.4: "UUID id/messageId"). Insert happens in the
 * SAME transaction as interaction admission / control intent / proposal
 * resolution; dispatch happens after commit, retransmitting the EXACT
 * stored envelope — never a rebuilt one.
 */
@Entity
@Table(name = "execution_command_outbox")
@Getter
@Setter
@NoArgsConstructor
public class ExecutionCommandOutbox {

    /** §3.4: the row id IS the wire messageId — assigned by the service
     * (never @GeneratedValue: the dispatcher retransmits this exact id). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** session_executions.id — the FK column, loaded manually (no JPA relation). */
    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    /** The owning session (§3.4: routing by session-owner node). */
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    /** The serving host instance (routing target). */
    @Column(name = "host_instance_id", nullable = false, updatable = false)
    private UUID hostInstanceId;

    /** §3.4: the envelope type discriminator, e.g. "execution.interaction". */
    @Column(name = "type", nullable = false, length = 64, updatable = false)
    private String type;

    /** §3.4: request/interaction correlation; nullable. */
    @Column(name = "correlation_id", length = 255, updatable = false)
    private String correlationId;

    /** §3.4: per-execution dispatch order (session stream sequence base). */
    @Column(name = "sequence", nullable = false, updatable = false)
    private Long sequence;

    /** §3.4: the exact stored wire envelope — retransmission reads THIS, never rebuilds. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "envelope", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> envelope;

    /** §3.4 digest over the stored envelope bytes. */
    @Column(name = "payload_digest", nullable = false, length = 64, updatable = false)
    private String payloadDigest;

    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status;

    /** §3.4: outbox lifetime is bounded by the execution deadline. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "next_delivery_at", nullable = false)
    private Instant nextDeliveryAt;

    @Column(name = "delivery_count", nullable = false)
    private Integer deliveryCount;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = OutboxStatus.PENDING;
        }
        if (deliveryCount == null) {
            deliveryCount = 0;
        }
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}