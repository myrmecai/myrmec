// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Governance Profile — persistent policy entity with a code natural key.
 *
 * <p>Three built-in profiles (STRICT, STANDARD, FLEXIBLE) are seeded via
 * Liquibase. The Org Admin selects one via {@code system_settings} key
 * {@code governance_profile_code}; each Project may select the same or a
 * stricter profile — never a looser one (the ratchet rule).</p>
 *
 * <p>See {@code governance-and-versioning.md} §2.</p>
 */
@Entity
@Table(name = "governance_profiles")
@Getter
@Setter
@NoArgsConstructor
public class GovernanceProfile {

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "is_built_in", nullable = false)
    private Boolean isBuiltIn;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "policies", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> policies;

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