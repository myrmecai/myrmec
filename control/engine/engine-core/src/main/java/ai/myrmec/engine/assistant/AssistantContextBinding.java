// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.assistant;

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
 * Assistant Context Binding — per-assistant-version context overrides.
 *
 * <p>Allows opting out of OPTIONAL instruction assets or binding a subset
 * of knowledge sources. {@code target_id} is a polymorphic FK pointing to
 * either {@code instruction_assets.id} or {@code knowledge_sources.id}
 * depending on {@code binding_type}. See UC-KM-11.</p>
 */
@Entity
@Table(name = "assistant_context_bindings")
@Getter
@Setter
@NoArgsConstructor
public class AssistantContextBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "assistant_version_id", nullable = false)
    private UUID assistantVersionId;

    @Column(name = "binding_type", nullable = false, length = 20)
    private String bindingType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

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