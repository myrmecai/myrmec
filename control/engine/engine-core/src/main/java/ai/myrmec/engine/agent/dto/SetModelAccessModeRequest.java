// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.ModelAccessMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for the PLATFORM_ADMIN-only model-access-mode change
 * endpoint (credential-envelope design &sect;5.1/&sect;5.2).
 */
@Data
public class SetModelAccessModeRequest {

    @NotNull(message = "Model access mode is required")
    private ModelAccessMode modelAccessMode;
}