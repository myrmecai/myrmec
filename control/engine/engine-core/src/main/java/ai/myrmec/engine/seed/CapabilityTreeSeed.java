// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.util.List;

/**
 * Seed DTO for a capability-tree node. Carries the inline {@code capabilities}
 * array (present on leaf nodes) which the importer turns into
 * {@code capability_mappings} rows; the capability keys live on the tree node,
 * not on a separate mappings file.
 *
 * @param id            stable natural key (e.g. {@code code.engineering.fs})
 * @param parentId      parent node id, null for domain roots
 * @param displayName   human-readable label
 * @param description   free-text description
 * @param active        whether the node is active
 * @param capabilities  capability keys exposed by this node (null/absent for roots)
 */
public record CapabilityTreeSeed(
        String id,
        String parentId,
        String displayName,
        String description,
        boolean active,
        List<String> capabilities) {
}
