// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetVersion;

import java.time.Instant;
import java.util.UUID;

public record InstructionAssetResponse(
        UUID id,
        String scope,
        UUID projectId,
        String name,
        String description,
        String category,
        String status,
        UUID currentVersionId,
        Instant publishedAt,
        UUID publishedBy,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy,
        String availability,
        String sourceType) {

    /**
     * Factory for the common case where only the parent asset is known
     * (e.g. project-scoped list, backward compatibility).  Availability
     * is {@code null} because the published version is not loaded.
     */
    public static InstructionAssetResponse from(InstructionAsset a) {
        return new InstructionAssetResponse(
                a.getId(),
                a.getScope(),
                a.getProjectId(),
                a.getName(),
                a.getDescription(),
                a.getCategory(),
                a.getStatus(),
                a.getCurrentVersionId(),
                a.getPublishedAt(),
                a.getPublishedBy(),
                a.getCreatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getUpdatedBy(),
                null,
                null);
    }

    /**
     * Factory that includes the published version's availability and source type — used by
     * the org-scoped list endpoint so the UI can show REQUIRED/OPTIONAL badges
     * and source type without a separate version fetch per asset.
     *
     * @param a                the parent instruction asset
     * @param publishedVersion the published version (may be {@code null} if
     *                         the asset has no published version yet)
     */
    public static InstructionAssetResponse from(InstructionAsset a, InstructionAssetVersion publishedVersion) {
        return from(a, publishedVersion, null);
    }

    /**
     * Factory that includes the published version's availability and source type,
     * falling back to the draft version when no published version exists.
     * Used by the org-scoped list endpoint so the UI can show source type and
     * availability for INCOMPLETE/Draft assets as well.
     *
     * @param a                the parent instruction asset
     * @param publishedVersion the published version (may be {@code null})
     * @param draftVersion     the draft version (may be {@code null})
     */
    public static InstructionAssetResponse from(InstructionAsset a, InstructionAssetVersion publishedVersion, InstructionAssetVersion draftVersion) {
        InstructionAssetVersion version = publishedVersion != null ? publishedVersion : draftVersion;
        return new InstructionAssetResponse(
                a.getId(),
                a.getScope(),
                a.getProjectId(),
                a.getName(),
                a.getDescription(),
                a.getCategory(),
                a.getStatus(),
                a.getCurrentVersionId(),
                a.getPublishedAt(),
                a.getPublishedBy(),
                a.getCreatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getUpdatedBy(),
                version != null ? version.getAvailability() : null,
                version != null ? version.getSourceType() : null);
    }
}