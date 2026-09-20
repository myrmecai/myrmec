package ai.myrmec.engine.agent.dto;

import ai.myrmec.engine.agent.AgentHostType;
import ai.myrmec.engine.agent.ModelAccessMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

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

    /**
     * Optional project scope. Null means system-wide agent.
     */
    private UUID projectId;

    /**
     * Maximum concurrent Agents (workers) this host may run.
     */
    @Min(value = 1, message = "Max agents must be at least 1")
    private Integer maxAgents = 1;

    /**
     * Model access mode (credential-envelope design §5): DIRECT or GATEWAY.
     * PLATFORM_ADMIN-only to change; absent means DIRECT (dev-phase default).
     */
    private ModelAccessMode modelAccessMode;

    /**
     * Durable host type (§2.3/§3.7): MANAGED (default) for cluster/headless
     * supervisors; LOCAL pre-provisions a per-user IDE seat — the owner is
     * unknown at creation and arrives when the user's plugin connects.
     * LOCAL hosts are per-user, never project-scoped.
     */
    private AgentHostType hostType;
}
