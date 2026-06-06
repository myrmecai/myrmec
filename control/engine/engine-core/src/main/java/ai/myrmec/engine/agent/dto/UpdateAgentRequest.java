package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.Agent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for updating an agent.
 */
@Data
public class UpdateAgentRequest {

    @Size(max = 100, message = "Agent name cannot exceed 100 characters")
    private String name;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    /**
     * Profile ID can be changed (agent role reassignment).
     */
    private UUID profileId;

    /**
     * Project scope can be changed.
     */
    private UUID projectId;

    /**
     * Model override. Set to empty string to clear.
     */
    @Size(max = 50, message = "Model override cannot exceed 50 characters")
    private String modelOverride;

    /**
     * Agent-specific configuration.
     */
    private Map<String, Object> config;

    /**
     * Maximum concurrent instances allowed.
     */
    @Min(value = 1, message = "Max instances must be at least 1")
    private Integer maxInstances;

    /**
     * Agent status.
     */
    private Agent.Status status;
}
