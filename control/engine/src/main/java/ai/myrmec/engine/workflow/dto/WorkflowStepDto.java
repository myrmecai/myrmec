package ai.myrmec.engine.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for workflow step definition (stored in JSONB).
 */
public record WorkflowStepDto(
        @NotBlank @Size(max = 50) String id,
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID agentProfileId,
        String prompt,
        List<String> dependsOn,
        Map<String, String> transitions,
        Integer timeoutSeconds,
        Integer maxRetries
) {
    public WorkflowStepDto {
        if (dependsOn == null) dependsOn = List.of();
        if (transitions == null) transitions = Map.of();
        if (timeoutSeconds == null) timeoutSeconds = 300;
        if (maxRetries == null) maxRetries = 0;
    }
}
