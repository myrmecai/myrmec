// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction.dto;

import jakarta.validation.constraints.Size;

public record UpdateInstructionAssetRequest(
        @Size(max = 100) String name,
        @Size(max = 2000) String description,
        String category) {
}