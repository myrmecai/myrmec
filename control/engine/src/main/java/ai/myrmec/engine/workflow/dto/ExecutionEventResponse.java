package ai.myrmec.engine.workflow.dto;

import ai.myrmec.engine.workflow.EventType;
import ai.myrmec.engine.workflow.LogSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for execution events.
 */
public record ExecutionEventResponse(
        UUID id,
        UUID taskId,
        UUID attemptId,
        EventType eventType,
        String logLevel,
        String message,
        Map<String, Object> data,
        Integer progress,
        String toolName,
        String toolCallId,
        Long durationMs,
        Boolean isError,
        LogSource source,
        Instant createdAt
) {
}
