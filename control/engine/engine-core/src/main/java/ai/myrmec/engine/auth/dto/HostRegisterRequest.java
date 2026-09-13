// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class HostRegisterRequest {
    @NotBlank
    private String registrationKey;
    private String hostname;
    private String runtimeVersion;
    private Map<String, Object> metadata;
}