package ai.myrmec.engine.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Request DTO for updating an agent profile.
 */
@Data
public class AgentProfileUpdateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    /**
     * List of software/hardware capabilities this profile supports.
     * E.g., ["python:3.11", "cuda:12.4", "docker"]
     */
    private List<String> capabilities;

    /**
     * List of supported tools/connectors (legacy field).
     * E.g., ["cli:kubectl", "api:openai", "db:postgresql"]
     * @deprecated Use toolCodes instead
     */
    private List<String> supportedTools;

    /**
     * Tool codes from the tools registry to assign to this profile.
     */
    private Set<String> toolCodes;

    /**
     * System prompt for agents using this profile.
     */
    private String systemPrompt;

    /**
     * Default model code for agents using this profile.
     */
    private String defaultModel;
}
