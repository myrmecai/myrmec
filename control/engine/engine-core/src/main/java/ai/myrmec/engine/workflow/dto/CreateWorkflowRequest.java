package ai.myrmec.engine.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateWorkflowRequest(
        @NotNull UUID projectId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @NotNull @Valid List<WorkflowStepDto> steps,
        Map<String, Object> inputSchema,
        @Valid ArtifactsRepoDto artifactsRepo
) {}
