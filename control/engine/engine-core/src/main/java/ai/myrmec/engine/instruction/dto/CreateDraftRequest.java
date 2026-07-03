// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateDraftRequest(
        @NotBlank @Size(max = 20) String sourceType,
        Map<String, Object> sourceDetails,
        UUID connectionConfigId,
        Map<String, Object> applicability,
        @Size(max = 20) String availability,
        Integer priority,
        Map<String, Object> activationRules) {
}