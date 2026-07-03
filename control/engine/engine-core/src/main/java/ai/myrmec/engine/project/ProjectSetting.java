// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import jakarta.persistence.Column;
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
import java.util.UUID;

/**
 * Project Setting — per-project key-value store.
 *
 * <p>Mirrors the {@code system_settings} pattern at project scope.
 * Typed accessors parse {@code setting_value} based on {@code value_type}.
 * Seeded with 8 default keys per UC-KM-13, UC-KM-14.</p>
 */
@Entity
@Table(name = "project_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProjectSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    @Column(name = "setting_value", columnDefinition = "text")
    private String settingValue;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "updated_by")
    private UUID updatedBy;

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