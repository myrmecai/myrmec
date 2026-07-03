// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

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
 * Instruction Asset Version — immutable-once-published behaviour contract.
 *
 * <p>Zone 2: source content + applicability rules. Source can be INLINE
 * (content stored in {@code source_details}) or GIT (repo URL on
 * {@code connection_config_id}, branch/paths in {@code source_details}).</p>
 */
@Entity
@Table(name = "instruction_asset_versions")
@Getter
@Setter
@NoArgsConstructor
public class InstructionAssetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "parent_version_id")
    private UUID parentVersionId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "source_details", columnDefinition = "jsonb")
    private Map<String, Object> sourceDetails;

    @Column(name = "connection_config_id")
    private UUID connectionConfigId;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "applicability", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> applicability;

    @Column(name = "availability", nullable = false, length = 20)
    private String availability;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "estimated_tokens")
    private Integer estimatedTokens;

    @Column(name = "git_commit", length = 40)
    private String gitCommit;

    @Column(name = "inline_version")
    private Integer inlineVersion;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "activation_rules", columnDefinition = "jsonb")
    private Map<String, Object> activationRules;

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