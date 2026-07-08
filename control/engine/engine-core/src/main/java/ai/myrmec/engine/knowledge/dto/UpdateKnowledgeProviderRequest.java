// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import jakarta.validation.constraints.Size;

public record UpdateKnowledgeProviderRequest(
        @Size(max = 100) String name,
        String description) {
}