package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        String description,
        List<WorkflowStepDto> steps,
        Map<String, Object> inputSchema,
        ArtifactsRepoDto artifactsRepo,
        Integer version,
        WorkflowStatus status,
        UUID createdById,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt
) {}
