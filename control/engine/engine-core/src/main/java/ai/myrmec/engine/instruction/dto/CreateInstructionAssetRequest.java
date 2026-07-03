// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateInstructionAssetRequest(
        @NotBlank @Size(max = 20) String scope,
        UUID projectId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 50) String category) {
}