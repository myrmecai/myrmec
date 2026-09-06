// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent Profile (design §16.1) — the Zone 1 identity and administrative
 * row of a versioned resource. Multiple agents can use the same profile.
 *
 * <p>Identity fields (name, description) and administrative fields
 * (status, is_system, addendum_allowed) live here. The complete behaviour
 * contract (system prompt, capabilities, tool set, default model,
 * interaction mode, orchestration policy) lives on immutable
 * {@link AgentProfileVersion} rows created through the Draft → Publish
 * cycle; runtime resolution always reads a published version row.
 */
@Entity
@Table(name = "agent_profiles")
@Getter
@Setter
@NoArgsConstructor
public class AgentProfile {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Unique profile name (e.g., "Python K8s Worker").
     */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * System profiles cannot be deleted.
     */
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    /**
     * Whether an Assistant built on this profile may supply an addendum prompt
     * (#92, assistant-entity.md §4.2). FALSE greys the addendum field in the
     * Assistant form and fails the publish gate if an addendum is set.
     * Administrative (a publish-gate input), not behaviour — stays on Zone 1.
     */
    @Column(name = "addendum_allowed", nullable = false)
    private boolean addendumAllowed = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

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
