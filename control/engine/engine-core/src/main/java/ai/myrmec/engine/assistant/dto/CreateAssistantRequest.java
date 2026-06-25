package ai.myrmec.engine.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Start-dialog create (#92, §8): a new assistant plus its initial Draft seeded
 * with the chosen brain. {@code name} is unique per project (case-insensitive).
 */
public record CreateAssistantRequest(
        @NotNull UUID projectId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 4000) String description,
        @NotNull UUID agentProfileId
) {
}
