// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeSourceResponse(
        UUID id, String scope, UUID projectId, String name, String description,
        String status, UUID providerVersionId, Map<String, Object> config,
        String availability, Integer priority, UUID createdBy,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public static KnowledgeSourceResponse from(KnowledgeSource s) {
        return new KnowledgeSourceResponse(s.getId(), s.getScope(), s.getProjectId(),
                s.getName(), s.getDescription(), s.getStatus(), s.getProviderVersionId(),
                s.getConfig(), s.getAvailability(), s.getPriority(), s.getCreatedBy(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getUpdatedBy());
    }
}