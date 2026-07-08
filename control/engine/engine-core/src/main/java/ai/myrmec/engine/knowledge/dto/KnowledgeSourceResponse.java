// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KnowledgeSourceResponse(
        UUID id, String name, String description,
        UUID providerVersionId, Map<String, Object> config,
        UUID createdBy, Instant createdAt, Instant updatedAt) {

    public static KnowledgeSourceResponse from(KnowledgeSource s) {
        return new KnowledgeSourceResponse(s.getId(), s.getName(), s.getDescription(),
                s.getProviderVersionId(), s.getConfig(),
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
    }
}