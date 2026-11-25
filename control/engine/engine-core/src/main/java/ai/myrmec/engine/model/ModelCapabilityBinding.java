// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.model;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Junction row binding a {@link Model} to a {@link ModelCapability}, with
 * optional per-capability parameters (e.g. {@code {"maxImages": 8}}).
 */
@Entity
@Table(name = "model_capability_bindings")
@Getter
@Setter
@NoArgsConstructor
public class ModelCapabilityBinding {

    @EmbeddedId
    private ModelCapabilityBindingId id;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "parameters", columnDefinition = "text")
    private Map<String, Object> parameters;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
