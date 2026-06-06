package ai.myrmec.engine.registration;

import ai.myrmec.engine._system.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin controller for managing registration keys.
 * TODO: Add proper admin authentication in later steps.
 */
@RestController
@RequestMapping("/api/v1/admin/registration-keys")
@RequiredArgsConstructor
@Tag(name = "Admin - Registration Keys", description = "Administrative operations for registration key management")
public class RegistrationKeyAdminController {

    private final RegistrationKeyService registrationKeyService;

    @Operation(
            summary = "Create a new registration key",
            description = "Create a new registration key for agent registration. The key value is only returned once and cannot be retrieved later."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registration key created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createKey(
            @Parameter(description = "Optional label for the key") @RequestParam(required = false) String label,
            @Parameter(description = "Key expiration in days (null = never expires)") @RequestParam(required = false) Integer expirationDays) {

        RegistrationKeyService.RegistrationKeyResult result =
                registrationKeyService.createKey(label, expirationDays);

        return ResponseEntity.ok(Map.of(
                "id", result.entity().getId(),
                "keyValue", result.plaintextKey(),  // Only returned once!
                "label", result.entity().getLabel() != null ? result.entity().getLabel() : "",
                "expiresAt", result.entity().getExpiresAt() != null
                        ? result.entity().getExpiresAt().toString() : "never"
        ));
    }

    @Operation(
            summary = "Revoke a registration key",
            description = "Revoke a registration key. Any agents registered with this key will have their tokens invalidated on next refresh."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Registration key revoked successfully"),
            @ApiResponse(responseCode = "404", description = "Registration key not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revokeKey(
            @Parameter(description = "Registration key ID") @PathVariable UUID keyId) {
        registrationKeyService.revokeKey(keyId);
        return ResponseEntity.noContent().build();
    }
}
