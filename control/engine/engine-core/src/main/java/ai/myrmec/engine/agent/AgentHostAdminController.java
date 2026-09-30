// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

import ai.myrmec.engine.agent.dto.AgentHostResponse;
import ai.myrmec.engine.agent.dto.AgentHostWithKeyResponse;
import ai.myrmec.engine.agent.dto.AgentResponse;
import ai.myrmec.engine.agent.dto.CreateAgentHostRequest;
import ai.myrmec.engine.agent.dto.SetModelAccessModeRequest;
import ai.myrmec.engine.agent.dto.UpdateAgentHostRequest;
import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin controller for managing agent hosts (the durable definition a
 * Supervisor process registers against).
 */
@RestController
@RequestMapping("/api/v1/admin/agent-hosts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('EDITOR')")
@Tag(name = "Agent Hosts (Admin)", description = "Agent host management operations")
public class AgentHostAdminController {

    private final AgentHostService agentHostService;
    private final ProjectRepository projectRepository;

    @Operation(summary = "List all agent hosts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of agent hosts"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<List<AgentHostResponse>> listAgentHosts() {
        List<AgentHost> agentHosts = agentHostService.findAll();

        // Fetch project names
        Map<UUID, String> projectNames = projectRepository.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));

        List<AgentHostResponse> responses = agentHosts.stream()
                .map(agentHost -> AgentHostResponse.from(
                        agentHost,
                        agentHost.getProjectId() != null ? projectNames.get(agentHost.getProjectId()) : null,
                        agentHostService.countOnlineInstances(agentHost.getId())
                ))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Get agent host by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent host found"),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<AgentHostResponse> getAgentHost(@PathVariable UUID id) {
        AgentHost agentHost = agentHostService.getAgent(id);
        String projectName = null;
        if (agentHost.getProjectId() != null) {
            projectName = projectRepository.findById(agentHost.getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }
        return ResponseEntity.ok(AgentHostResponse.from(
                agentHost,
                projectName,
                agentHostService.countOnlineInstances(agentHost.getId())
        ));
    }

    @Operation(summary = "List the Agents (warm replicas) of an agent host, with their runtime FSM status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of Agents"),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/agents")
    public ResponseEntity<List<AgentResponse>> listAgents(@PathVariable UUID id) {
        // Validates the host exists (throws 404 otherwise) before listing.
        agentHostService.getAgent(id);
        List<AgentResponse> agents = agentHostService.getAgentsForAgentHost(id).stream()
                .map(AgentResponse::from)
                .toList();
        return ResponseEntity.ok(agents);
    }

    @Operation(summary = "Create a new agent host")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Agent host created with registration key"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<AgentHostWithKeyResponse> createAgentHost(
            @Valid @RequestBody CreateAgentHostRequest request) {

        // Credential-envelope design §5.1: the model access mode is a
        // PLATFORM_ADMIN-only setting; the class-level guard also allows
        // EDITORs, so the mode field itself is gated here.
        ai.myrmec.engine.agent.ModelAccessMode mode =
                request.getModelAccessMode() == null ? null
                        : requirePlatformAdminForMode(request.getModelAccessMode());

        AgentHostCreationResult result = agentHostService.createAgent(
                request.getName(),
                request.getDescription(),
                request.getProjectId(),
                request.getMaxAgents(),
                mode,
                // Local-owner model (§3.7): MANAGED default; LOCAL allowed to
                // pre-provision a per-user IDE seat (owner arrives at
                // host.open). Host type is immutable after creation.
                request.getHostType()
        );

        String projectName = null;
        if (result.agent().getProjectId() != null) {
            projectName = projectRepository.findById(result.agent().getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }

        AgentHostResponse agentHostResponse = AgentHostResponse.from(result.agent(), projectName, 0);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentHostWithKeyResponse.builder()
                        .agentHost(agentHostResponse)
                        .registrationKey(result.registrationKey())
                        .build());
    }

    @Operation(summary = "Update agent host")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent host updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<AgentHostResponse> updateAgentHost(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgentHostRequest request) {

        // Credential-envelope design §5.1: mode changes are PLATFORM_ADMIN
        // only. EDITORs may edit other fields via this endpoint, but a
        // non-null mode in the body is rejected unless they are an admin.
        ai.myrmec.engine.agent.ModelAccessMode mode =
                request.getModelAccessMode() == null ? null
                        : requirePlatformAdminForMode(request.getModelAccessMode());

        AgentHost agentHost = agentHostService.updateAgent(
                id,
                request.getName(),
                request.getDescription(),
                request.getProjectId(),
                request.getMaxAgents(),
                request.getStatus(),
                mode
        );

        String projectName = null;
        if (agentHost.getProjectId() != null) {
            projectName = projectRepository.findById(agentHost.getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }

        return ResponseEntity.ok(AgentHostResponse.from(
                agentHost,
                projectName,
                agentHostService.countOnlineInstances(agentHost.getId())
        ));
    }

    @Operation(summary = "Delete agent host")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agent host deleted"),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgentHost(@PathVariable UUID id) {
        agentHostService.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Regenerate registration key for an agent host")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New registration key generated"),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/regenerate-key")
    public ResponseEntity<Map<String, String>> regenerateKey(@PathVariable UUID id) {
        String newKey = agentHostService.regenerateRegistrationKey(id);
        return ResponseEntity.ok(Map.of("registrationKey", newKey));
    }

    /**
     * Credential-envelope design §5.1/§5.2: change a host's model access
     * mode. PLATFORM_ADMIN only — the class-level guard also admits EDITORs,
     * so this endpoint tightens with a method-level guard.
     */
    @Operation(summary = "Set the model access mode of an agent host (PLATFORM_ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Model access mode updated"),
            @ApiResponse(responseCode = "400", description = "Mode rejected by the validation matrix",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a platform admin, or governance mandate violated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Agent host not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PutMapping("/{id}/model-access-mode")
    public ResponseEntity<AgentHostResponse> setModelAccessMode(
            @PathVariable UUID id,
            @Valid @RequestBody SetModelAccessModeRequest request) {
        AgentHost agentHost = agentHostService.updateAgent(
                id, null, null, null, null, null, request.getModelAccessMode());

        String projectName = null;
        if (agentHost.getProjectId() != null) {
            projectName = projectRepository.findById(agentHost.getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }
        return ResponseEntity.ok(AgentHostResponse.from(
                agentHost,
                projectName,
                agentHostService.countOnlineInstances(agentHost.getId())
        ));
    }

    /**
     * Runtime guard for the PLATFORM_ADMIN-only mode field on the shared
     * create/update endpoints (design §5.1 — the class-level guard admits
     * EDITORs, but the mode setting is platform-level only).
     */
    private ai.myrmec.engine.agent.ModelAccessMode requirePlatformAdminForMode(
            ai.myrmec.engine.agent.ModelAccessMode mode) {
        var principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof ai.myrmec.engine.user.UserPrincipal user
                ? user : null;
        if (principal == null || !principal.isPlatformAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Changing the model access mode requires PLATFORM_ADMIN");
        }
        return mode;
    }
}