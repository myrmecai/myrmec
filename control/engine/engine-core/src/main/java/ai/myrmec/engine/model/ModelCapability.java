// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Model capability reference (design §model-capabilities) — a catalog of
 * discrete, reusable capabilities a {@link Model} may expose (e.g.
 * {@code INPUT_TEXT}, {@code STREAMING}, {@code TOOL_USE}). Bound to models
 * via {@code model_capability_bindings}.
 *
 * <p>{@code code} is a stable, human-readable natural key following the
 * reference/config-data convention.</p>
 */
@Entity
@Table(name = "model_capabilities")
@Getter
@Setter
@NoArgsConstructor
public class ModelCapability {

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModelStatus status = ModelStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
