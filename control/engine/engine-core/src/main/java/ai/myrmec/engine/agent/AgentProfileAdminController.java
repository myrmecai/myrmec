package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.agent.dto.AgentProfileCreateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileResponse;
import ai.myrmec.engine.agent.dto.AgentProfileUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin controller for managing agent profiles.
 */
@RestController
@RequestMapping("/api/v1/admin/agent-profiles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('EDITOR')")
@Tag(name = "Agent Profiles (Admin)", description = "Agent profile management operations")
public class AgentProfileAdminController {

    private final AgentProfileService profileService;

    @Operation(summary = "List all agent profiles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of agent profiles"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<List<AgentProfileResponse>> listProfiles(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        List<AgentProfile> profiles = activeOnly
                ? profileService.getActiveProfiles()
                : profileService.getAllProfiles();
        return ResponseEntity.ok(profiles.stream()
                .map(AgentProfileResponse::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "Get agent profile by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent profile found"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<AgentProfileResponse> getProfile(@PathVariable UUID id) {
        AgentProfile profile = profileService.getProfile(id);
        return ResponseEntity.ok(AgentProfileResponse.from(profile));
    }

    @Operation(summary = "Create a new agent profile")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Agent profile created"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<AgentProfileResponse> createProfile(
            @Valid @RequestBody AgentProfileCreateRequest request) {
        AgentProfile profile = profileService.createProfile(
                request.getName(),
                request.getDescription(),
                request.getCapabilities(),
                request.getSupportedTools(),
                request.getToolCodes(),
                request.getSystemPrompt(),
                request.getDefaultModel()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentProfileResponse.from(profile));
    }

    @Operation(summary = "Update an agent profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent profile updated"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<AgentProfileResponse> updateProfile(
            @PathVariable UUID id,
            @Valid @RequestBody AgentProfileUpdateRequest request) {
        AgentProfile profile = profileService.updateProfile(
                id,
                request.getName(),
                request.getDescription(),
                request.getCapabilities(),
                request.getSupportedTools(),
                request.getToolCodes(),
                request.getSystemPrompt(),
                request.getDefaultModel()
        );
        return ResponseEntity.ok(AgentProfileResponse.from(profile));
    }

    @Operation(summary = "Delete an agent profile")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agent profile deleted"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Agent profile is in use",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable UUID id) {
        profileService.deleteProfile(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Deactivate an agent profile")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agent profile deactivated"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Agent profile is in use",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateProfile(@PathVariable UUID id) {
        profileService.deactivateProfile(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Activate an agent profile")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agent profile activated"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activateProfile(@PathVariable UUID id) {
        profileService.activateProfile(id);
        return ResponseEntity.noContent().build();
    }
}
