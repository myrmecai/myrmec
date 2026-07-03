// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateKnowledgeSourceRequest(
        @NotBlank @Size(max = 20) String scope,
        UUID projectId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotBlank UUID providerVersionId,
        Map<String, Object> config,
        @Size(max = 20) String availability,
        Integer priority) {
}