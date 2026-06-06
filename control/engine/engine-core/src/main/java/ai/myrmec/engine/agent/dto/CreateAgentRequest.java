package ai.myrmec.engine.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating an agent.
 */
@Data
public class CreateAgentRequest {

    @NotBlank(message = "Agent name is required")
    @Size(max = 100, message = "Agent name cannot exceed 100 characters")
    private String name;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    private String description;

    @NotNull(message = "Profile ID is required")
    private UUID profileId;

    /**
     * Optional project scope. Null means system-wide agent.
     */
    private UUID projectId;

    /**
     * Optional model override. Null uses profile's default model.
     */
    @Size(max = 50, message = "Model override cannot exceed 50 characters")
    private String modelOverride;

    /**
     * Optional agent-specific configuration.
     */
    private Map<String, Object> config;

    /**
     * Maximum concurrent instances allowed.
     */
    @Min(value = 1, message = "Max instances must be at least 1")
    private Integer maxInstances = 1;
}
