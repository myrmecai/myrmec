// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import ai.myrmec.engine.instruction.InstructionAsset;

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
        UUID updatedBy) {

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
                a.getUpdatedBy());
    }
}