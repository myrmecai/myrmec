// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

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
 * Data Feed — flat model. Syncs raw content into a Managed Knowledge Provider.
 *
 * <p>Feeds and sources are defined independently — linked by matching
 * {@code dataset_name} to {@code knowledge_sources.config.datasetName}.
 * The {@code connection_config_id} handles URL + auth;
 * {@code connection_details} stores type-specific params beyond the connection.</p>
 */
@Entity
@Table(name = "data_feeds")
@Getter
@Setter
@NoArgsConstructor
public class DataFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "provider_version_id", nullable = false)
    private UUID providerVersionId;

    @Column(name = "dataset_name", nullable = false, length = 200)
    private String datasetName;

    @Column(name = "connection_config_id")
    private UUID connectionConfigId;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "connection_details", columnDefinition = "jsonb")
    private Map<String, Object> connectionDetails;

    @Column(name = "sync_schedule", length = 100)
    private String syncSchedule;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

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