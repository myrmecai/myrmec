package ai.myrmec.engine.auth;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.auth.dto.RefreshRequest;
import ai.myrmec.engine.auth.dto.RefreshResponse;
import ai.myrmec.engine.auth.dto.RegisterRequest;
import ai.myrmec.engine.auth.dto.RegisterResponse;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication controller for agent registration and token refresh.
 */
@RestController
@RequestMapping("/api/v1/agent/auth")
@RequiredArgsConstructor
@Tag(name = "Agent Authentication", description = "Agent registration and token management")
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "Register a new agent",
            description = "Register a new agent using a valid registration key. Returns access and refresh tokens."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent registered successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired registration key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements // No auth required for registration
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @RequestHeader("X-Registration-Key") String registrationKey,
            @Valid @RequestBody RegisterRequest request) {
        request.setRegistrationKey(registrationKey);
        RegisterResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Refresh access token",
            description = "Get a new access token using a valid refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token refreshed successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirements // No auth required for refresh
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }
}
