// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * {@code POST /api/v1/auth/authorize-code} — the hosted login page
 * redeems its logged-in JWT for a one-time code that the desktop
 * client (VS Code plugin) can exchange on its own loopback listener.
 */
@Data
public class AuthorizeCodeRequest {

    /**
     * The plugin's loopback callback URI ({@code http://127.0.0.1:<port>}).
     * Recorded on the code; the exchange must present the same value.
     */
    @NotBlank
    @Size(max = 512)
    private String redirectUri;
}