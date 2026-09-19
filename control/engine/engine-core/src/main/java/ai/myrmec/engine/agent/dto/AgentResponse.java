package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentHost;
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
    private UUID projectId;
    private String projectName;
    private String modelOverride;
    private Map<String, Object> config;
    private Integer maxAgents;
    private AgentHost.Status status;
    private ai.myrmec.engine.agent.ModelAccessMode modelAccessMode;
    private int activeInstanceCount;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     */
    public static AgentResponse from(AgentHost agent) {
        return from(agent, null, 0);
    }

    /**
     * Convert entity to response DTO with project name.
     */
    public static AgentResponse from(AgentHost agent, String projectName, int activeInstanceCount) {
        return AgentResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .projectId(agent.getProjectId())
                .projectName(projectName)
                .modelOverride(agent.getModelOverride())
                .config(agent.getConfig())
                .maxAgents(agent.getMaxAgents())
                .status(agent.getStatus())
                .modelAccessMode(agent.getModelAccessMode())
                .activeInstanceCount(activeInstanceCount)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }
}
