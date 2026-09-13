// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class HostRefreshRequest {
    @NotBlank
    private String refreshToken;
}
