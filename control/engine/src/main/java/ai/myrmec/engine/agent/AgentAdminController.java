package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine.agent.dto.AgentResponse;
import ai.myrmec.engine.agent.dto.AgentWithKeyResponse;
import ai.myrmec.engine.agent.dto.CreateAgentRequest;
import ai.myrmec.engine.agent.dto.UpdateAgentRequest;
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
 * Admin controller for managing agents.
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('EDITOR')")
@Tag(name = "Agents (Admin)", description = "Agent management operations")
public class AgentAdminController {

    private final AgentService agentService;
    private final AgentProfileService profileService;
    private final ProjectRepository projectRepository;

    @Operation(summary = "List all agents")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of agents"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<List<AgentResponse>> listAgents() {
        List<Agent> agents = agentService.findAll();

        // Fetch profile names
        Map<UUID, String> profileNames = profileService.getAllProfiles().stream()
                .collect(Collectors.toMap(AgentProfile::getId, AgentProfile::getName));

        // Fetch project names
        Map<UUID, String> projectNames = projectRepository.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));

        List<AgentResponse> responses = agents.stream()
                .map(agent -> AgentResponse.from(
                        agent,
                        profileNames.get(agent.getProfileId()),
                        agent.getProjectId() != null ? projectNames.get(agent.getProjectId()) : null,
                        agentService.countOnlineInstances(agent.getId())
                ))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Get agent by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent found"),
            @ApiResponse(responseCode = "404", description = "Agent not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getAgent(@PathVariable UUID id) {
        Agent agent = agentService.getAgent(id);
        AgentProfile profile = profileService.getProfile(agent.getProfileId());
        String projectName = null;
        if (agent.getProjectId() != null) {
            projectName = projectRepository.findById(agent.getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }
        return ResponseEntity.ok(AgentResponse.from(
                agent,
                profile.getName(),
                projectName,
                agentService.countOnlineInstances(agent.getId())
        ));
    }

    @Operation(summary = "Create a new agent")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Agent created with registration key"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<AgentWithKeyResponse> createAgent(
            @Valid @RequestBody CreateAgentRequest request) {

        AgentCreationResult result = agentService.createAgent(
                request.getName(),
                request.getDescription(),
                request.getProfileId(),
                request.getProjectId(),
                request.getModelOverride(),
                request.getConfig(),
                request.getMaxInstances()
        );

        AgentProfile profile = profileService.getProfile(result.agent().getProfileId());
        String projectName = null;
        if (result.agent().getProjectId() != null) {
            projectName = projectRepository.findById(result.agent().getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }

        AgentResponse agentResponse = AgentResponse.from(result.agent(), profile.getName(), projectName, 0);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentWithKeyResponse.builder()
                        .agent(agentResponse)
                        .registrationKey(result.registrationKey())
                        .build());
    }

    @Operation(summary = "Update agent")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Agent not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<AgentResponse> updateAgent(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgentRequest request) {

        Agent agent = agentService.updateAgent(
                id,
                request.getName(),
                request.getDescription(),
                request.getProfileId(),
                request.getProjectId(),
                request.getModelOverride(),
                request.getConfig(),
                request.getMaxInstances(),
                request.getStatus()
        );

        AgentProfile profile = profileService.getProfile(agent.getProfileId());
        String projectName = null;
        if (agent.getProjectId() != null) {
            projectName = projectRepository.findById(agent.getProjectId())
                    .map(Project::getName)
                    .orElse(null);
        }

        return ResponseEntity.ok(AgentResponse.from(
                agent,
                profile.getName(),
                projectName,
                agentService.countOnlineInstances(agent.getId())
        ));
    }

    @Operation(summary = "Delete agent")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agent deleted"),
            @ApiResponse(responseCode = "404", description = "Agent not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable UUID id) {
        agentService.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Regenerate registration key for an agent")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New registration key generated"),
            @ApiResponse(responseCode = "404", description = "Agent not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/regenerate-key")
    public ResponseEntity<Map<String, String>> regenerateKey(@PathVariable UUID id) {
        String newKey = agentService.regenerateRegistrationKey(id);
        return ResponseEntity.ok(Map.of("registrationKey", newKey));
    }
}
