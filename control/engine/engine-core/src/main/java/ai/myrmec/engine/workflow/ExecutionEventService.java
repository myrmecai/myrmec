package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.ExecutionEventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Service for managing execution events (logs, progress, tool calls).
 * 
 * <p>Events are:
 * <ul>
 *   <li>Persisted to database for audit trail</li>
 *   <li>Published to SSE subscribers for live monitoring</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventService {

    private final ExecutionEventRepository eventRepository;
    private final WorkflowTaskRepository taskRepository;

    /**
     * Subscribers for live events, keyed by request ID.
     * Each subscriber is a callback that receives events.
     */
    private final Map<UUID, CopyOnWriteArrayList<Consumer<ExecutionEvent>>> subscribers = 
            new ConcurrentHashMap<>();

    /**
     * Cache: task ID → request ID for efficient event routing.
     */
    private final Map<UUID, UUID> taskToRequestCache = new ConcurrentHashMap<>();

    // ==================== Event Recording ====================

    /**
     * Record a log event from an agent.
     */
    @Transactional
    public ExecutionEvent recordLog(UUID taskId, String level, String message, Map<String, Object> data) {
        return recordLog(taskId, level, message, data, LogSource.TASK);
    }

    /**
     * Record a log event from an agent with source.
     */
    @Transactional
    public ExecutionEvent recordLog(UUID taskId, String level, String message, Map<String, Object> data, LogSource source) {
        ExecutionEvent event = ExecutionEvent.log(taskId, level, message, data, source);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a log event from an agent with attempt ID.
     */
    @Transactional
    public ExecutionEvent recordLog(UUID taskId, UUID attemptId, String level, String message, 
                                    Map<String, Object> data, LogSource source) {
        ExecutionEvent event = ExecutionEvent.log(taskId, attemptId, level, message, data, source);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a progress update from an agent.
     */
    @Transactional
    public ExecutionEvent recordProgress(UUID taskId, int progress, String message) {
        ExecutionEvent event = ExecutionEvent.progress(taskId, progress, message);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a progress update from an agent with attempt ID.
     */
    @Transactional
    public ExecutionEvent recordProgress(UUID taskId, UUID attemptId, int progress, String message) {
        ExecutionEvent event = ExecutionEvent.progress(taskId, attemptId, progress, message);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a tool call start.
     */
    @Transactional
    public ExecutionEvent recordToolCall(UUID taskId, String callId, String toolName, Map<String, Object> input) {
        ExecutionEvent event = ExecutionEvent.toolCall(taskId, callId, toolName, input);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a tool call start with attempt ID.
     */
    @Transactional
    public ExecutionEvent recordToolCall(UUID taskId, UUID attemptId, String callId, 
                                         String toolName, Map<String, Object> input) {
        ExecutionEvent event = ExecutionEvent.toolCall(taskId, attemptId, callId, toolName, input);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a tool call result.
     */
    @Transactional
    public ExecutionEvent recordToolResult(UUID taskId, String callId, String toolName, 
                                           Long durationMs, boolean isError, String error) {
        ExecutionEvent event = ExecutionEvent.toolResult(taskId, callId, toolName, durationMs, isError, error);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a tool call result with attempt ID.
     */
    @Transactional
    public ExecutionEvent recordToolResult(UUID taskId, UUID attemptId, String callId, String toolName, 
                                           Long durationMs, boolean isError, String error) {
        ExecutionEvent event = ExecutionEvent.toolResult(taskId, attemptId, callId, toolName, durationMs, isError, error);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a status change.
     */
    @Transactional
    public ExecutionEvent recordStatusChange(UUID taskId, TaskStatus fromStatus, TaskStatus toStatus, String message) {
        ExecutionEvent event = ExecutionEvent.statusChange(
                taskId, 
                fromStatus != null ? fromStatus.name() : null, 
                toStatus.name(), 
                message);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a status change with attempt ID.
     */
    @Transactional
    public ExecutionEvent recordStatusChange(UUID taskId, UUID attemptId, TaskStatus fromStatus, 
                                             TaskStatus toStatus, String message) {
        ExecutionEvent event = ExecutionEvent.statusChange(
                taskId, 
                attemptId,
                fromStatus != null ? fromStatus.name() : null, 
                toStatus.name(), 
                message);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a TOKEN_USAGE event from an LLM call.
     */
    @Transactional
    public ExecutionEvent recordTokenUsage(UUID taskId, UUID attemptId, String model, String callId,
                                           Integer promptTokens, Integer completionTokens,
                                           Integer totalTokens, Long durationMs) {
        ExecutionEvent event = ExecutionEvent.tokenUsage(taskId, attemptId, model, callId,
                promptTokens, completionTokens, totalTokens, durationMs);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    /**
     * Record a TASK_METRICS summary event.
     */
    @Transactional
    public ExecutionEvent recordTaskMetrics(UUID taskId, UUID attemptId, Map<String, Object> metrics) {
        ExecutionEvent event = ExecutionEvent.taskMetrics(taskId, attemptId, metrics);
        event = eventRepository.save(event);
        publishEvent(event);
        return event;
    }

    // ==================== Event Retrieval ====================

    /**
     * Get all events for a task.
     */
    public List<ExecutionEvent> getEventsForTask(UUID taskId) {
        return eventRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    /**
     * Get events for a task after a timestamp (for incremental fetching).
     */
    public List<ExecutionEvent> getEventsForTaskAfter(UUID taskId, Instant after) {
        return eventRepository.findByTaskIdAndCreatedAtAfterOrderByCreatedAtAsc(taskId, after);
    }

    /**
     * Get all events for a request (all tasks).
     */
    public List<ExecutionEvent> getEventsForRequest(UUID requestId) {
        return eventRepository.findByRequestId(requestId);
    }

    /**
     * Get events for a request after a timestamp.
     */
    public List<ExecutionEvent> getEventsForRequestAfter(UUID requestId, Instant after) {
        return eventRepository.findByRequestIdAndCreatedAtAfter(requestId, after);
    }

    /**
     * Get events for a task as response DTOs.
     */
    @Transactional(readOnly = true)
    public List<ExecutionEventResponse> findByTaskId(UUID taskId) {
        return eventRepository.findByTaskIdOrderByCreatedAtAsc(taskId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get events for an attempt as response DTOs.
     */
    @Transactional(readOnly = true)
    public List<ExecutionEventResponse> findByAttemptId(UUID attemptId) {
        return eventRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private ExecutionEventResponse toResponse(ExecutionEvent event) {
        return new ExecutionEventResponse(
                event.getId(),
                event.getTaskId(),
                event.getAttemptId(),
                event.getEventType(),
                event.getLogLevel(),
                event.getMessage(),
                event.getData(),
                event.getProgress(),
                event.getToolName(),
                event.getToolCallId(),
                event.getDurationMs(),
                event.getIsError(),
                event.getSource(),
                event.getCreatedAt()
        );
    }

    // ==================== SSE Subscription ====================

    /**
     * Subscribe to live events for a request.
     * Returns a runnable to unsubscribe.
     */
    public Runnable subscribeToRequest(UUID requestId, Consumer<ExecutionEvent> subscriber) {
        subscribers.computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        log.debug("Added SSE subscriber for request {}, total: {}", 
                requestId, subscribers.get(requestId).size());
        
        return () -> {
            CopyOnWriteArrayList<Consumer<ExecutionEvent>> list = subscribers.get(requestId);
            if (list != null) {
                list.remove(subscriber);
                if (list.isEmpty()) {
                    subscribers.remove(requestId);
                }
                log.debug("Removed SSE subscriber for request {}, remaining: {}", 
                        requestId, list.size());
            }
        };
    }

    /**
     * Publish an event to subscribers.
     * Called after persisting the event.
     */
    private void publishEvent(ExecutionEvent event) {
        // Look up request ID for this task
        UUID requestId = getRequestIdForTask(event.getTaskId());
        if (requestId != null) {
            publishEventToRequest(requestId, event);
        }
    }

    /**
     * Get the request ID for a task (for routing events to subscribers).
     * Uses a cache to avoid repeated database lookups.
     */
    private UUID getRequestIdForTask(UUID taskId) {
        return taskToRequestCache.computeIfAbsent(taskId, id -> {
            return taskRepository.findById(id)
                    .map(task -> task.getRequest().getId())
                    .orElse(null);
        });
    }

    /**
     * Publish an event to subscribers for a specific request.
     */
    public void publishEventToRequest(UUID requestId, ExecutionEvent event) {
        CopyOnWriteArrayList<Consumer<ExecutionEvent>> list = subscribers.get(requestId);
        if (list != null) {
            for (Consumer<ExecutionEvent> subscriber : list) {
                try {
                    subscriber.accept(event);
                } catch (Exception e) {
                    log.warn("Error publishing event to subscriber: {}", e.getMessage());
                }
            }
        }
    }
}
