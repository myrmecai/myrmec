// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * {@code POST /api/v1/auth/code/exchange} — the desktop client (VS
 * Code plugin) redeems the one-time code from its loopback listener
 * for a fresh user token pair. No authentication: the code itself is
 * the bearer (single-use, short TTL, client-bound).
 */
@Data
public class ExchangeCodeRequest {

    @NotBlank
    private String code;

    /**
     * The same loopback URI the code was issued for — a code can only
     * be redeemed by the client it was bound to.
     */
    @NotBlank
    private String redirectUri;
}