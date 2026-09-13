// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class HostLocalRegisterRequest {
    /** Project scope, or null for a system-wide local host. */
    private UUID projectId;
    private String hostname;
    private String runtimeVersion;
    private Map<String, Object> metadata;
}