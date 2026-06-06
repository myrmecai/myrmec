package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.TaskResult;
import ai.myrmec.engine.workflow.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkflowTaskResponse(
        UUID id,
        UUID requestId,
        String stepId,
        UUID agentProfileId,
        String agentProfileName,
        UUID agentInstanceId,
        Map<String, Object> input,
        Map<String, Object> output,
        TaskStatus status,
        TaskResult result,
        String errorMessage,
        Integer attempt,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        // New: attempts support
        UUID currentAttemptId,
        List<TaskAttemptResponse> attempts,
        // New: aggregated metrics (tokens, timing, cost)
        Map<String, Object> metrics
) {
    /**
     * Create response without attempts (for backward compatibility).
     */
    public WorkflowTaskResponse(
            UUID id,
            UUID requestId,
            String stepId,
            UUID agentProfileId,
            String agentProfileName,
            UUID agentInstanceId,
            Map<String, Object> input,
            Map<String, Object> output,
            TaskStatus status,
            TaskResult result,
            String errorMessage,
            Integer attempt,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
        this(id, requestId, stepId, agentProfileId, agentProfileName, agentInstanceId,
                input, output, status, result, errorMessage, attempt,
                createdAt, startedAt, completedAt, null, null, null);
    }

    /**
     * Create response with metrics (no attempts).
     */
    public WorkflowTaskResponse(
            UUID id,
            UUID requestId,
            String stepId,
            UUID agentProfileId,
            String agentProfileName,
            UUID agentInstanceId,
            Map<String, Object> input,
            Map<String, Object> output,
            TaskStatus status,
            TaskResult result,
            String errorMessage,
            Integer attempt,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Map<String, Object> metrics
    ) {
        this(id, requestId, stepId, agentProfileId, agentProfileName, agentInstanceId,
                input, output, status, result, errorMessage, attempt,
                createdAt, startedAt, completedAt, null, null, metrics);
    }
}
