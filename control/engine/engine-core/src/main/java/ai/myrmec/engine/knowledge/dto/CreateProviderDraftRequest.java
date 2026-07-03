// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateProviderDraftRequest(
        UUID connectionConfigId,
        Map<String, Object> config) {
}