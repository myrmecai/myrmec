package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Row shape used by the cross-Team workflow list view.
 * Includes the workflow's Team name and a summary of its most recent run.
 */
public record WorkflowListItemResponse(
        UUID id,
        String name,
        String description,
        Integer version,
        WorkflowStatus status,
        UUID teamId,
        String teamName,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt,
        UUID lastRunId,
        RequestStatus lastRunStatus,
        Instant lastRunAt
) {}
