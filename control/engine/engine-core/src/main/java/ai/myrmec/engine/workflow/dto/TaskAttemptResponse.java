package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.AttemptStatus;
import ai.myrmec.engine.workflow.TaskResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for task attempt.
 */
public record TaskAttemptResponse(
        UUID id,
        UUID taskId,
        Integer attemptNumber,
        UUID agentInstanceId,
        AttemptStatus status,
        TaskResult result,
        Map<String, Object> output,
        String errorMessage,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {}
