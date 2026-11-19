// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Junction row linking a capability key to a capability-tree node. A single
 * capability may be mapped to many tree nodes (and vice versa).
 */
@Entity
@Table(name = "capability_mappings")
@Getter
@Setter
@NoArgsConstructor
public class CapabilityMapping {

    @EmbeddedId
    private CapabilityMappingId id;
}
