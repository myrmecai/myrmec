package ai.myrmec.engine.serviceaccount;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.serviceaccount.dto.CreateServiceAccountRequest;
import ai.myrmec.engine.serviceaccount.dto.ServiceAccountResponse;
import ai.myrmec.engine.serviceaccount.dto.UpdateServiceAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin provisioning for External-API service accounts (#95).
 *
 * <p>Restricted to {@code PLATFORM_ADMIN}/{@code ORG_ADMIN} by the
 * {@code /api/v1/admin/**} rule in {@code SecurityConfig}. A service account
 * maps a Keycloak client to a single project; the assistants it may open are
 * further gated by {@code assistant_grants(USE)} ∩ {@code EXTERNAL_API ∈ usable_via}.
 * V1 disables accounts (kill switch) rather than hard-deleting them.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/service-accounts")
@RequiredArgsConstructor
@Tag(name = "Admin - Service Accounts", description = "Provisioning for External API service accounts (#95)")
public class ServiceAccountAdminController {

    private final ServiceAccountService serviceAccountService;

    @Operation(summary = "Provision a new service account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Service account created"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Project not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Keycloak client id already in use",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ServiceAccountResponse> create(
            @Valid @RequestBody CreateServiceAccountRequest request,
            @CurrentUser UUID userId) {
        ServiceAccount sa = serviceAccountService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceAccountResponse.of(sa));
    }

    @Operation(summary = "List service accounts, optionally filtered by project")
    @GetMapping
    public ResponseEntity<List<ServiceAccountResponse>> list(
            @Parameter(description = "Optional project filter")
            @RequestParam(required = false) UUID projectId) {
        List<ServiceAccountResponse> body = serviceAccountService.list(projectId).stream()
                .map(ServiceAccountResponse::of)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Get a single service account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<ServiceAccountResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ServiceAccountResponse.of(serviceAccountService.get(id)));
    }

    @Operation(summary = "Update a service account (enable/disable, rename, retune rate limit)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<ServiceAccountResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceAccountRequest request) {
        return ResponseEntity.ok(ServiceAccountResponse.of(serviceAccountService.update(id, request)));
    }
}
