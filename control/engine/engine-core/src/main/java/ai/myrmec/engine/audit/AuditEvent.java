// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

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
 * Audit Event — append-only event log for all versioned entity state changes.
 *
 * <p>Shared across the platform. BIGINT PK (sequence, append-optimized).
 * {@code event_type} is generic (no entity prefix) — {@code entity_type}
 * disambiguates. {@code before_snapshot} / {@code after_snapshot} store
 * only changed fields, not full rows.</p>
 *
 * <p><strong>Append-only:</strong> no UPDATE, no DELETE. The {@code id}
 * and {@code timestamp} are set at creation and never modified.</p>
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_display_name", nullable = false, length = 255)
    private String actorDisplayName;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> afterSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * Hash-chain: the previous event's {@code eventHash} in the same scope.
     * {@code null} for unchained rows (pre-activation / chaining off) and
     * for the genesis row (first chained event in a segment, uses the
     * genesis constant instead).
     */
    @Column(name = "prev_event_hash", length = 64, updatable = false, nullable = true)
    private String prevEventHash;

    /**
     * Hash-chain: HMAC-SHA256 of {@code canonical(prevEventHash ‖ fields)}.
     * {@code null} for unchained rows (pre-activation / chaining off).
     * Immutable after insert — JPA {@code updatable=false} prevents
     * dirty-checking from overwriting it.
     */
    @Column(name = "event_hash", length = 64, updatable = false, nullable = true)
    private String eventHash;

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}