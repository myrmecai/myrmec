// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * {@code POST /api/v1/auth/authorize-code} response: the one-time
 * plaintext code and its expiry. The code is returned exactly once and
 * never persisted in plaintext.
 */
@Data
@Builder
public class AuthorizeCodeResponse {

    /** One-time code ({@code myr_auth_*}); the plugin redeems it once. */
    private String code;

    /** When the code expires (issue time + TTL). */
    private Instant expiresAt;
}