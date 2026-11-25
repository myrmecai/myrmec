// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import lombok.Data;

import java.util.Map;

/**
 * Optional registration metadata for the LOCAL host branch. Ownership and
 * team membership come from the user bearer token (the minted HOST token
 * carries the ownerUserId claim); the body only carries display/telemetry
 * fields. Every field is optional — the endpoint works with no body at all.
 */
@Data
public class HostLocalRegisterRequest {
    private String hostname;
    private String runtimeVersion;
    private Map<String, Object> metadata;
}