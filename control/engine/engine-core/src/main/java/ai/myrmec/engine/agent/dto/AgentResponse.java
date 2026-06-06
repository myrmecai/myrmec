package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.Agent;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for agent data.
 */
@Data
@Builder
public class AgentResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID profileId;
    private String profileName;
    private UUID projectId;
    private String projectName;
    private String modelOverride;
    private Map<String, Object> config;
    private Integer maxInstances;
    private Agent.Status status;
    private int activeInstanceCount;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     */
    public static AgentResponse from(Agent agent) {
        return from(agent, null, null, 0);
    }

    /**
     * Convert entity to response DTO with profile and project names.
     */
    public static AgentResponse from(Agent agent, String profileName, String projectName, int activeInstanceCount) {
        return AgentResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .profileId(agent.getProfileId())
                .profileName(profileName)
                .projectId(agent.getProjectId())
                .projectName(projectName)
                .modelOverride(agent.getModelOverride())
                .config(agent.getConfig())
                .maxInstances(agent.getMaxInstances())
                .status(agent.getStatus())
                .activeInstanceCount(activeInstanceCount)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }
}
