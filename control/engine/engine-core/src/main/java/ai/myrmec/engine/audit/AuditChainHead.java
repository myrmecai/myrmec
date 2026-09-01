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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-scope chain state for the tamper-evident audit log.
 *
 * <p>One row per (scopeType, projectId) pair. Holds the current head hash
 * and serves as the {@code FOR UPDATE} lock point to prevent
 * fork-on-concurrent-insert (design decision D8).
 *
 * <p><strong>Verification never reads this table</strong> — it recomputes
 * from {@code audit_events} only. This table is a write-time optimization
 * (lock + head pointer), not a verification-time authority.
 */
@Entity
@Table(name = "audit_chain_heads")
@Getter
@Setter
@NoArgsConstructor
public class AuditChainHead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Scope type: "ORGANIZATION" or "PROJECT". */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /** Project ID (null for ORG scope). */
    @Column(name = "project_id")
    private UUID projectId;

    /** Current head hash (the last event's {@code event_hash}). */
    @Column(name = "head_hash", length = 64)
    private String headHash;

    /** Whether chaining is currently active for this scope. */
    @Column(name = "chaining_active", nullable = false)
    private boolean chainingActive;

    /** When this head row was created (first chain activation). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When this head row was last updated. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** JPA optimistic-lock version (for concurrent updates). */
    @Version
    @Column(name = "version_column")
    private Long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}