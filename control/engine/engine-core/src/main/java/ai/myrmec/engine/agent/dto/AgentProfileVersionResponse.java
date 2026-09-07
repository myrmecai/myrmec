// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentProfileVersion;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Response DTO for one agent-profile version (the Draft/Publish lifecycle
 * surface, design §16.1): DRAFT (the single open draft), PUBLISHED (the
 * immutable runtime pin target), ARCHIVED (history). Zone-2 behaviour
 * fields resolve from the version row; the orchestration policy maps
 * (§7 command templates / §17.4 approval policy) are NOT exposed here —
 * they are engine-side contract data consumed by the assignment
 * assembler, never edited through this DTO.
 */
public record AgentProfileVersionResponse(
        UUID id,
        UUID profileId,
        Integer versionNumber,
        String status,
        java.util.List<String> capabilities,
        Set<String> toolCodes,
        String systemPrompt,
        String defaultModel,
        String interactionMode,
        Instant publishedAt,
        UUID publishedBy,
        Instant createdAt) {

    public static AgentProfileVersionResponse from(AgentProfileVersion version) {
        return new AgentProfileVersionResponse(
                version.getId(),
                version.getProfileId(),
                version.getVersionNumber(),
                version.getStatus() != null ? version.getStatus().name() : null,
                version.getCapabilities() != null ? version.getCapabilities() : java.util.List.of(),
                version.getTools() != null
                        ? version.getTools().stream()
                            .map(ai.myrmec.engine.tool.Tool::getCode)
                            .collect(Collectors.toSet())
                        : Set.of(),
                version.getSystemPrompt(),
                version.getDefaultModel(),
                version.getInteractionMode() != null ? version.getInteractionMode().name() : null,
                version.getPublishedAt(),
                version.getPublishedBy(),
                version.getCreatedAt());
    }
}