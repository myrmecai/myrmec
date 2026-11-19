// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite key for {@link CapabilityMapping}: a capability key plus the
 * tree node that exposes it.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CapabilityMappingId implements Serializable {

    @Column(name = "capability_key", nullable = false, length = 100)
    private String capabilityKey;

    @Column(name = "capability_tree_id", nullable = false, length = 50)
    private String capabilityTreeId;
}
