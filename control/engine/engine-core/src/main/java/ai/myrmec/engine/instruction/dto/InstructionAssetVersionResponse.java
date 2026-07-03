// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import ai.myrmec.engine.instruction.InstructionAssetVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record InstructionAssetVersionResponse(
        UUID id,
        UUID assetId,
        Integer versionNumber,
        UUID parentVersionId,
        String status,
        String sourceType,
        Map<String, Object> sourceDetails,
        UUID connectionConfigId,
        Map<String, Object> applicability,
        String availability,
        Integer priority,
        Integer estimatedTokens,
        String gitCommit,
        Integer inlineVersion,
        Map<String, Object> activationRules,
        UUID draftOwnerId,
        Instant publishedAt,
        UUID publishedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static InstructionAssetVersionResponse from(InstructionAssetVersion v) {
        return new InstructionAssetVersionResponse(
                v.getId(),
                v.getAssetId(),
                v.getVersionNumber(),
                v.getParentVersionId(),
                v.getStatus(),
                v.getSourceType(),
                v.getSourceDetails(),
                v.getConnectionConfigId(),
                v.getApplicability(),
                v.getAvailability(),
                v.getPriority(),
                v.getEstimatedTokens(),
                v.getGitCommit(),
                v.getInlineVersion(),
                v.getActivationRules(),
                v.getDraftOwnerId(),
                v.getPublishedAt(),
                v.getPublishedBy(),
                v.getCreatedAt(),
                v.getUpdatedAt());
    }
}