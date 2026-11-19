// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of the unified {@code POST /api/v1/agent/auth/host/register}:
 * the durable host row id, a short-lived HOST credential pair, and the
 * flavour the engine resolved — {@code MANAGED} (Authorization: Key) or
 * {@code LOCAL} (Authorization: Bearer). LOCAL responses carry the owner
 * (the registering user); the minted access token carries the same value
 * as its ownerUserId claim.
 */
@Data
@Builder
public class HostRegisterResponse {
    private UUID hostId;
    private String accessToken;
    private Instant accessTokenExpiresAt;
    private String refreshToken;
    private Instant refreshTokenExpiresAt;
    /** "MANAGED" (registration key) or "LOCAL" (user bearer). */
    private String hostType;
    /** LOCAL only: the owning user (the token's ownerUserId claim). Null for MANAGED. */
    private UUID ownerUserId;
}