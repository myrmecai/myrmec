package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.ModelAccessMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

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
     * Project scope can be changed.
     */
    private UUID projectId;

    /**
     * Maximum concurrent Agents (workers) this host may run.
     */
    @Min(value = 1, message = "Max agents must be at least 1")
    private Integer maxAgents;

    /**
     * Agent status.
     */
    private AgentHost.Status status;

    /**
     * Model access mode change (credential-envelope design §5). PLATFORM_ADMIN
     * only — the controller method-level guard rejects EDITORs; null = no change.
     */
    private ModelAccessMode modelAccessMode;
}
