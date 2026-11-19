// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A node in the capability tree (design §capability-tree). Nodes form a
 * two-level hierarchy: domain roots (e.g. {@code code.engineering},
 * {@code devops}) with leaf children (e.g. {@code code.engineering.fs}).
 * The {@link CapabilityMapping} junction links each leaf to the capability
 * keys it exposes; a capability may be exposed by more than one node.
 *
 * <p>{@code id} is a stable, human-readable natural key (e.g.
 * {@code code.engineering.fs}), following the reference/config-data
 * convention. {@code parentId} is stored as a plain column per the
 * engine's "avoid JPA relationships" rule.</p>
 */
@Entity
@Table(name = "capability_tree")
@Getter
@Setter
@NoArgsConstructor
public class CapabilityTree {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    /** Parent node id; null for domain roots. */
    @Column(name = "parent_id", length = 50)
    private String parentId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
