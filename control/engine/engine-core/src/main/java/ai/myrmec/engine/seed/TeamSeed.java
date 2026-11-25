// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.util.List;
import java.util.UUID;

/**
 * Seed DTO for a Team, read from {@code seed-data/optional/teams.json}.
 * Mirrors the {@code CreateTeamRequest} shape (server-managed fields like
 * id/status/timestamps are omitted).
 */
public record TeamSeed(
        String name,
        String description,
        List<String> allowedServiceTypes,
        UUID groupId,
        String workspaceRepoUrl,
        String workspaceRepoBranch,
        Boolean autoHitlOnDestructive,
        Boolean attachmentsEnabled,
        Integer attachmentRetentionTtlDays,
        Long attachmentMaxFileSizeBytes,
        String attachmentTypeAllowlist) {
}
