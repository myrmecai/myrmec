package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.agent.dto.AgentProfileCreateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileDraftUpdateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileResponse;
import ai.myrmec.engine.agent.dto.AgentProfileUpdateRequest;
import ai.myrmec.engine.agent.dto.AgentProfileVersionResponse;
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
    private final AgentProfileVersionService versionService;

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
                .map(p -> AgentProfileResponse.from(
                        p,
                        versionService.findPublishedWithTools(p.getId()).orElse(null),
                        versionService.findOpenDraft(p.getId()).orElse(null)))
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
        return ResponseEntity.ok(AgentProfileResponse.from(
                profile,
                versionService.findPublishedWithTools(id).orElse(null),
                versionService.findOpenDraft(id).orElse(null)));
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
        // §7/§17.4: the orchestration policy content (command templates +
        // approval policy) rides the published version 1 when supplied —
        // the dedicated overload publishes ONE version carrying it.
        AgentProfile profile = (request.getCommandTemplates() != null
                || request.getApprovalPolicy() != null
                || request.getApprovalRequestTtlSeconds() != null)
                ? profileService.createProfile(
                        request.getName(),
                        request.getDescription(),
                        request.getCapabilities(),
                        request.getToolCodes(),
                        request.getSystemPrompt(),
                        request.getDefaultModel(),
                        request.getCommandTemplates(),
                        request.getApprovalPolicy(),
                        request.getApprovalRequestTtlSeconds())
                : profileService.createProfile(
                        request.getName(),
                        request.getDescription(),
                        request.getCapabilities(),
                        request.getSupportedTools(),
                        request.getToolCodes(),
                        request.getSystemPrompt(),
                        request.getDefaultModel()
                );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentProfileResponse.from(
                        profile, versionService.findPublishedWithTools(profile.getId()).orElse(null)));
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
        return ResponseEntity.ok(AgentProfileResponse.from(
                profile, versionService.findPublishedWithTools(id).orElse(null)));
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

    // ==================== Draft/Publish version lifecycle (§16.1) ====================

    @Operation(summary = "List an agent profile's versions (newest first)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version list"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<AgentProfileVersionResponse>> listVersions(@PathVariable UUID id) {
        profileService.getProfile(id); // 404 when the profile is absent
        return ResponseEntity.ok(versionService.listVersions(id).stream()
                .map(AgentProfileVersionResponse::from)
                .toList());
    }

    @Operation(summary = "Get the single open draft")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The open draft version"),
            @ApiResponse(responseCode = "404", description = "No open draft",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/draft")
    public ResponseEntity<AgentProfileVersionResponse> getDraft(@PathVariable UUID id) {
        return ResponseEntity.ok(AgentProfileVersionResponse.from(versionService.getOpenDraft(id)));
    }

    @Operation(summary = "Open a new draft by cloning the currently published version (409 if a draft is open)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft opened"),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A draft is already open",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/versions")
    public ResponseEntity<AgentProfileVersionResponse> openDraft(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentProfileVersionResponse.from(versionService.createDraft(id)));
    }

    @Operation(summary = "Edit the open draft's behaviour fields (PATCH semantics)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved draft"),
            @ApiResponse(responseCode = "400", description = "Version is not a draft",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No open draft",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{id}/draft")
    public ResponseEntity<AgentProfileVersionResponse> updateDraft(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody AgentProfileDraftUpdateRequest request) {
        var draft = versionService.getOpenDraft(id);
        // PATCH semantics: null fields keep the draft's current value.
        var saved = versionService.updateDraft(
                draft.getId(),
                request.getCapabilities() != null
                        ? request.getCapabilities() : draft.getCapabilities(),
                request.getToolCodes(),
                request.getSystemPrompt() != null
                        ? request.getSystemPrompt() : draft.getSystemPrompt(),
                request.getDefaultModel() != null
                        ? request.getDefaultModel() : draft.getDefaultModel(),
                request.getInteractionMode() != null
                        ? AgentProfileVersion.InteractionMode.valueOf(request.getInteractionMode())
                        : draft.getInteractionMode());
        return ResponseEntity.ok(AgentProfileVersionResponse.from(saved));
    }

    @Operation(summary = "Discard the open draft, freeing the single-draft slot")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Draft discarded"),
            @ApiResponse(responseCode = "404", description = "No open draft",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}/draft")
    public ResponseEntity<Void> discardDraft(@PathVariable UUID id) {
        versionService.discardDraft(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Publish the open draft (freezes it, archives the previous published version)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The newly published version"),
            @ApiResponse(responseCode = "400", description = "No open draft or no changes to publish",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Agent profile not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/publish")
    public ResponseEntity<AgentProfileVersionResponse> publish(
            @PathVariable UUID id,
            @ai.myrmec.engine._system.security.CurrentUser UUID publisherId) {
        var published = versionService.publish(id, publisherId);
        return ResponseEntity.ok(AgentProfileVersionResponse.from(published));
    }
}
