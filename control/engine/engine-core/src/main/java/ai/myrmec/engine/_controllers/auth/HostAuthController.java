// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._controllers.auth;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.exception.InvalidRegistrationKeyException;
import ai.myrmec.engine._system.exception.InvalidTokenException;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.auth.HostAuthService;
import ai.myrmec.engine.auth.dto.HostLocalRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRefreshRequest;
import ai.myrmec.engine.auth.dto.HostRefreshResponse;
import ai.myrmec.engine.auth.dto.HostRegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Host authentication for the unified agent protocol (§4.1): one
 * scheme-discriminated registration endpoint + refresh-token rotation.
 * These endpoints mint HOST_JWT credentials; they create no agent row and
 * no host instance.
 *
 * <p>Registration credential discrimination (the credential NEVER travels in
 * the body — the body carries only registration metadata):
 * <ul>
 *   <li>{@code Authorization: Key myr_agent_…} — MANAGED host bootstrap: the
 *       registration key is the credential.</li>
 *   <li>{@code Authorization: Bearer <user jwt>} — LOCAL host bootstrap: the
 *       logged-in user's token is the credential; the minted HOST token
 *       carries the ownerUserId claim (engine-verified ownership).</li>
 * </ul>
 * The route is permitAll (the JwtAuthenticationFilter still populates the
 * SecurityContext for a valid Bearer); the Key scheme is anonymous to the
 * filter, which is exactly why it reaches the controller undecoded.
 */
@RestController
@RequestMapping("/api/v1/agent/auth/host")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Agent Host Authentication", description = "Unified protocol §4.1: host registration and refresh")
public class HostAuthController {

    private final HostAuthService hostAuthService;

    @Operation(
            summary = "Register a host (scheme-discriminated: managed or local)",
            description = "Authorization: Key <registration key> registers the MANAGED host the key "
                    + "belongs to; Authorization: Bearer <user jwt> registers the calling user's "
                    + "LOCAL host. Both branches mint HOST_JWT + refresh tokens and create no "
                    + "agent-execution-slot row and no host-instance row."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Host registered successfully"),
            @ApiResponse(responseCode = "400", description = "Missing Authorization header",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credential (key, bearer, or scheme)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient Team access (LOCAL branch)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<HostRegisterResponse> register(
            @Valid @RequestBody(required = false) HostLocalRegisterRequest body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (authorization == null || authorization.isBlank()) {
            throw new BadRequestException("Authorization header is required "
                    + "(Key <registration key> or Bearer <user token>)");
        }

        // Case-insensitive scheme parse (RFC 7235): "bearer"/"Bearer" are
        // equivalent; the scheme decides the branch, the value is the
        // credential.
        String trimmed = authorization.trim();
        int split = trimmed.indexOf(' ');
        String scheme = split == -1 ? "" : trimmed.substring(0, split).toLowerCase();
        String value = split == -1 ? "" : trimmed.substring(split + 1).trim();

        if ("key".equals(scheme)) {
            return ResponseEntity.ok(hostAuthService.registerManaged(value));
        }

        if ("bearer".equals(scheme)) {
            // The filter has already validated the bearer and populated the
            // SecurityContext; an absent/anonymous principal means the token
            // was invalid or expired (or not a user token) — fail closed.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || !(authentication.getPrincipal() instanceof UserPrincipal user)) {
                throw new InvalidTokenException("Invalid or expired token.");
            }
            UUID userId = user.getUserId();
            // Ownership + team membership come from the bearer token, not the
            // body: the minted HOST token carries the ownerUserId claim and the
            // user's Team-scoped roles are already on the principal. The body
            // (if any) carries only display/telemetry fields.
            HostLocalRegisterRequest request = body != null ? body : new HostLocalRegisterRequest();
            return ResponseEntity.ok(hostAuthService.registerLocal(userId, request.getHostname()));
        }

        throw new InvalidRegistrationKeyException("Unsupported Authorization scheme");
    }

    @Operation(
            summary = "Rotate host refresh token",
            description = "Present a live host refresh token to receive a rotated pair. "
                    + "Replay of a consumed token revokes the token family."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token rotated successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid, replayed, or revoked refresh token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<HostRefreshResponse> refresh(@Valid @RequestBody HostRefreshRequest request) {
        return ResponseEntity.ok(hostAuthService.refresh(request));
    }
}