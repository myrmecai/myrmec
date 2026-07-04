// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.secret.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSecretMetadataRequest(
        @NotBlank(message = "Name is required.")
        @Size(max = 200, message = "Name must be at most 200 characters.")
        String name
) {
}