// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeProviderVersion;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeProviderVersionResponse(
        UUID id, UUID providerId, Integer versionNumber, UUID parentVersionId,
        String status, UUID connectionConfigId, Map<String, Object> config,
        UUID draftOwnerId, Instant publishedAt, UUID publishedBy,
        Instant createdAt, Instant updatedAt) {

    public static KnowledgeProviderVersionResponse from(KnowledgeProviderVersion v) {
        return new KnowledgeProviderVersionResponse(v.getId(), v.getProviderId(),
                v.getVersionNumber(), v.getParentVersionId(), v.getStatus(),
                v.getConnectionConfigId(), v.getConfig(), v.getDraftOwnerId(),
                v.getPublishedAt(), v.getPublishedBy(), v.getCreatedAt(), v.getUpdatedAt());
    }
}