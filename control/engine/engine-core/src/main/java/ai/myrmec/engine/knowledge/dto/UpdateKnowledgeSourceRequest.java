// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateKnowledgeSourceRequest(
        @Size(max = 100) String name,
        String description,
        Map<String, Object> config) {
}