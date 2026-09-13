// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.auth.dto.HostLocalRegisterRequest;
import ai.myrmec.engine.auth.dto.HostRefreshRequest;
import ai.myrmec.engine.auth.dto.HostRefreshResponse;
import ai.myrmec.engine.auth.dto.HostRegisterRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Host authentication for the unified agent protocol (§4.1): managed and
 * local host registration plus refresh-token rotation. These endpoints mint
 * HOST_JWT credentials; they create no agent row and no host instance.
 */
@RestController
@RequestMapping("/api/v1/agent/auth/host")
@RequiredArgsConstructor
@Tag(name = "Agent Host Authentication", description = "Unified protocol §4.1: host registration and refresh")
public class HostAuthController {

    private final HostAuthService hostAuthService;

    @Operation(
            summary = "Register a managed agent host",
            description = "Validate a registration key and issue HOST_JWT + refresh tokens. "
                    + "Creates no agent-execution-slot row and no host-instance row."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Host registered successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid registration key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<HostRegisterResponse> register(@Valid @RequestBody HostRegisterRequest request) {
        return ResponseEntity.ok(hostAuthService.registerManaged(request));
    }

    @Operation(
            summary = "Register the calling user's local agent host",
            description = "Resolve or create the user's local host and issue HOST tokens. "
                    + "Requires the user's access token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Local host registered successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid user token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient project access",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PreAuthorize("(#request.projectId == null or @projectAccess.canEdit(#request.projectId, authentication))")
    @PostMapping("/local/register")
    public ResponseEntity<HostRegisterResponse> registerLocal(
            @Valid @RequestBody HostLocalRegisterRequest request,
            @RequestHeader(value = "X-Local-Agent-Hostname", required = false) String hostname,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(hostAuthService.registerLocal(userId, request, hostname));
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
