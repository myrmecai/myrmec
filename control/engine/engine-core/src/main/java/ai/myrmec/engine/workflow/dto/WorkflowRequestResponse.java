package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.RequestStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WorkflowRequestResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        Integer workflowVersion,
        Map<String, Object> input,
        Map<String, Object> output,
        RequestStatus status,
        String errorMessage,
        UUID createdById,
        String createdByEmail,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
