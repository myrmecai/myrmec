// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
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
 * Session — unified session model for inference dispatch (§15.3).
 *
 * <p>One row per conversation or workflow execution session. Stores the
 * pinned context (instruction asset version IDs + knowledge source IDs)
 * so mid-session republish can't change behaviour.</p>
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "service_type", nullable = false, length = 20)
    private String serviceType;  // "CONVERSATION" or "WORKFLOW"

    @Column(name = "ref_id", nullable = false)
    private UUID refId;  // conversation_id or workflow_request_id

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @org.hibernate.annotations.JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_pins", columnDefinition = "jsonb")
    private Map<String, Object> contextPins;

    @Column(name = "status", nullable = false, length = 20)
    private String status;  // "ACTIVE" or "CLOSED"

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (status == null) status = "ACTIVE";
    }
}