// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of the AI context pinned at session/conversation creation.
 *
 * <p>Stored in {@code conversations.context_snapshot} when the active
 * governance profile has {@code CONTEXT_PINNING=ON} (runtime name
 * {@code PINNED_AT_START}). Used by {@link ContextBuilder} to reproduce the
 * same instruction asset versions across all turns of the session.</p>
 *
 * @param governanceProfileCode     the effective governance profile code at creation
 * @param instructionAssetVersionIds published version IDs to pin
 * @param knowledgeSourceIds        active knowledge source IDs to pin
 */
public record ContextSnapshot(
        String governanceProfileCode,
        List<UUID> instructionAssetVersionIds,
        List<UUID> knowledgeSourceIds
) {
}