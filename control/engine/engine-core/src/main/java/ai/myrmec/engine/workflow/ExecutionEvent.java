package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ExecutionEvent entity - captures logs, progress updates, and tool calls during task execution.
 * 
 * <p>Events are streamed from agents via WebSocket and stored for:
 * <ul>
 *   <li>Live monitoring in the UI (via SSE)</li>
 *   <li>Historical audit trail</li>
 *   <li>Debugging failed executions</li>
 * </ul>
 */
@Entity
@Table(name = "execution_events", indexes = {
    @Index(name = "idx_execution_events_task_id", columnList = "task_id, created_at"),
    @Index(name = "idx_execution_events_attempt_id", columnList = "attempt_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Task this event belongs to.
     */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /**
     * Attempt this event belongs to (null for legacy events).
     * <p>New events should always have this set.
     */
    @Column(name = "attempt_id")
    private UUID attemptId;

    /**
     * Type of event.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType eventType;

    /**
     * Log level for LOG events.
     */
    @Column(name = "log_level", length = 10)
    private String logLevel;

    /**
     * Main message or description.
     */
    @Column(name = "message", columnDefinition = "text")
    private String message;

    /**
     * Structured data (tool input/output, progress info, etc.).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "data")
    private Map<String, Object> data;

    /**
     * Progress percentage (0-100) for PROGRESS events.
     */
    @Column(name = "progress")
    private Integer progress;

    /**
     * Tool name for TOOL_CALL/TOOL_RESULT events.
     */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    /**
     * Tool call ID to correlate calls with results.
     */
    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    /**
     * Duration in milliseconds for TOOL_RESULT events.
     */
    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Whether the tool call resulted in an error.
     */
    @Column(name = "is_error")
    private Boolean isError;

    /**
     * Source of the log (TASK, AGENT, SYSTEM).
     * Defaults to TASK for backward compatibility.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LogSource source = LogSource.TASK;

    /**
     * When this event occurred.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Create a LOG event.
     */
    public static ExecutionEvent log(UUID taskId, String level, String message, Map<String, Object> data) {
        return log(taskId, null, level, message, data, LogSource.TASK);
    }

    /**
     * Create a LOG event with source.
     */
    public static ExecutionEvent log(UUID taskId, String level, String message, Map<String, Object> data, LogSource source) {
        return log(taskId, null, level, message, data, source);
    }

    /**
     * Create a LOG event with attempt and source.
     */
    public static ExecutionEvent log(UUID taskId, UUID attemptId, String level, String message, Map<String, Object> data, LogSource source) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.LOG);
        event.setLogLevel(level);
        event.setMessage(message);
        event.setData(data);
        event.setSource(source != null ? source : LogSource.TASK);
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a PROGRESS event.
     */
    public static ExecutionEvent progress(UUID taskId, int progress, String message) {
        return progress(taskId, null, progress, message);
    }

    /**
     * Create a PROGRESS event with attempt.
     */
    public static ExecutionEvent progress(UUID taskId, UUID attemptId, int progress, String message) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.PROGRESS);
        event.setProgress(progress);
        event.setMessage(message);
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a TOOL_CALL event.
     */
    public static ExecutionEvent toolCall(UUID taskId, String callId, String toolName, Map<String, Object> input) {
        return toolCall(taskId, null, callId, toolName, input);
    }

    /**
     * Create a TOOL_CALL event with attempt.
     */
    public static ExecutionEvent toolCall(UUID taskId, UUID attemptId, String callId, String toolName, Map<String, Object> input) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.TOOL_CALL);
        event.setToolCallId(callId);
        event.setToolName(toolName);
        event.setData(input);
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a TOOL_RESULT event.
     */
    public static ExecutionEvent toolResult(UUID taskId, String callId, String toolName, 
                                            Long durationMs, boolean isError, String error) {
        return toolResult(taskId, null, callId, toolName, durationMs, isError, error);
    }

    /**
     * Create a TOOL_RESULT event with attempt.
     */
    public static ExecutionEvent toolResult(UUID taskId, UUID attemptId, String callId, String toolName, 
                                            Long durationMs, boolean isError, String error) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.TOOL_RESULT);
        event.setToolCallId(callId);
        event.setToolName(toolName);
        event.setDurationMs(durationMs);
        event.setIsError(isError);
        if (isError && error != null) {
            event.setMessage(error);
        }
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a STATUS_CHANGE event.
     */
    public static ExecutionEvent statusChange(UUID taskId, String fromStatus, String toStatus, String message) {
        return statusChange(taskId, null, fromStatus, toStatus, message);
    }

    /**
     * Create a STATUS_CHANGE event with attempt.
     */
    public static ExecutionEvent statusChange(UUID taskId, UUID attemptId, String fromStatus, String toStatus, String message) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.STATUS_CHANGE);
        event.setMessage(message);
        event.setData(Map.of("fromStatus", fromStatus != null ? fromStatus : "", "toStatus", toStatus));
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a TOKEN_USAGE event.
     */
    public static ExecutionEvent tokenUsage(UUID taskId, UUID attemptId, String model, String callId,
                                            Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                            Long durationMs) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.TOKEN_USAGE);
        event.setToolCallId(callId);
        event.setDurationMs(durationMs);
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        if (model != null) data.put("model", model);
        if (promptTokens != null) data.put("promptTokens", promptTokens);
        if (completionTokens != null) data.put("completionTokens", completionTokens);
        if (totalTokens != null) data.put("totalTokens", totalTokens);
        event.setData(data);
        event.setCreatedAt(Instant.now());
        return event;
    }

    /**
     * Create a TASK_METRICS event from a metrics map.
     */
    public static ExecutionEvent taskMetrics(UUID taskId, UUID attemptId, Map<String, Object> metrics) {
        ExecutionEvent event = new ExecutionEvent();
        event.setTaskId(taskId);
        event.setAttemptId(attemptId);
        event.setEventType(EventType.TASK_METRICS);
        event.setData(metrics);
        event.setCreatedAt(Instant.now());
        return event;
    }
}
