// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.auth.dto.AuthorizeCodeRequest;
import ai.myrmec.engine.auth.dto.AuthorizeCodeResponse;
import ai.myrmec.engine.auth.dto.ExchangeCodeRequest;
import ai.myrmec.engine.user.dto.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Desktop-client login flow (VS Code plugin). The hosted login page —
 * already holding the user's JWT — calls {@code authorize-code} to
 * mint a one-time code; the plugin's loopback listener receives it via
 * browser redirect and redeems it at {@code code/exchange} for its own
 * token pair. Codes are single-use, short-TTL, client-bound, and
 * stored hashed; any engine replica can issue or exchange (shared
 * state lives in {@code auth_codes}, no replica affinity).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "User Authentication", description = "Desktop-client authorization codes")
public class AuthCodeController {

    private final AuthCodeService authCodeService;

    @Operation(
            summary = "Issue a one-time authorization code (desktop client login)",
            description = "The hosted login page, holding the logged-in user JWT, "
                    + "redeems it for a one-time code bound to the plugin's loopback redirect."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code issued"),
            @ApiResponse(responseCode = "400", description = "redirectUri is not an http loopback URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/authorize-code")
    public ResponseEntity<AuthorizeCodeResponse> authorizeCode(
            @CurrentUser UUID userId,
            @Valid @RequestBody AuthorizeCodeRequest request) {
        AuthCodeService.IssuedAuthCode issued = authCodeService.issue(userId, request.getRedirectUri());
        return ResponseEntity.ok(AuthorizeCodeResponse.builder()
                .code(issued.code())
                .expiresAt(issued.expiresAt())
                .build());
    }

    @Operation(
            summary = "Exchange a one-time authorization code for a token pair",
            description = "The desktop client redeems the code captured by its loopback listener. "
                    + "The code is single-use and short-TTL; a fresh access+refresh token pair "
                    + "is minted for the user the code was issued to."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token pair issued"),
            @ApiResponse(responseCode = "400", description = "redirectUri is not an http loopback URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unknown, used, or expired code",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements // the code itself is the bearer
    @PostMapping("/code/exchange")
    public ResponseEntity<LoginResponse> exchangeCode(
            @Valid @RequestBody ExchangeCodeRequest request) {
        LoginResponse tokens = authCodeService.exchange(request.getCode(), request.getRedirectUri());
        return ResponseEntity.ok(tokens);
    }
}