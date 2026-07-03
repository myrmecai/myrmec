// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeProvider;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeProviderResponse(
        UUID id, String scope, UUID projectId, String name, String description,
        String type, String status, UUID currentVersionId,
        Instant publishedAt, UUID publishedBy, UUID createdBy,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public static KnowledgeProviderResponse from(KnowledgeProvider p) {
        return new KnowledgeProviderResponse(p.getId(), p.getScope(), p.getProjectId(),
                p.getName(), p.getDescription(), p.getType(), p.getStatus(),
                p.getCurrentVersionId(), p.getPublishedAt(), p.getPublishedBy(),
                p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt(), p.getUpdatedBy());
    }
}