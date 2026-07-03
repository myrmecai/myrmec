// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Connection Config Version — immutable-once-published behaviour contract.
 *
 * <p>Zone 2: behaviour contract. At most one DRAFT and one PUBLISHED per
 * parent (enforced by partial unique indexes). The {@code config} column
 * stores type-specific connectivity params as JSONB.</p>
 */
@Entity
@Table(name = "connection_config_versions")
@Getter
@Setter
@NoArgsConstructor
public class ConnectionConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "connection_config_id", nullable = false)
    private UUID connectionConfigId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "parent_version_id")
    private UUID parentVersionId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "url", length = 2000)
    private String url;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "draft_owner_id")
    private UUID draftOwnerId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}