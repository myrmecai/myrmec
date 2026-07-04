// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateConnectionConfigRequest(
        @Size(max = 100) String name,
        @Size(max = 2000) String description,
        UUID credentialSecretId) {
}