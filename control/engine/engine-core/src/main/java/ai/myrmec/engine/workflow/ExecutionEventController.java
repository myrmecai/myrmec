package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.ExecutionEventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST and SSE endpoints for execution events.
 * 
 * <p>Provides:
 * <ul>
 *   <li>REST endpoints for fetching historical events</li>
 *   <li>SSE endpoint for live event streaming</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows/{workflowId}/requests/{requestId}/events")
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventController {

    private final ExecutionEventService eventService;
    private final WorkflowService workflowService;
    private final WorkflowTaskService taskService;

    /**
     * Get all events for a request.
     */
    @GetMapping
    public List<ExecutionEventResponse> getEvents(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @RequestParam(required = false) String after) {
        
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }

        List<ExecutionEvent> events;
        if (after != null && !after.isEmpty()) {
            Instant afterInstant = Instant.parse(after);
            events = eventService.getEventsForRequestAfter(requestId, afterInstant);
        } else {
            events = eventService.getEventsForRequest(requestId);
        }

        return events.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get events for a specific task.
     */
    @GetMapping("/tasks/{taskId}")
    public List<ExecutionEventResponse> getTaskEvents(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @RequestParam(required = false) String after) {
        
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }

        List<ExecutionEvent> events;
        if (after != null && !after.isEmpty()) {
            Instant afterInstant = Instant.parse(after);
            events = eventService.getEventsForTaskAfter(taskId, afterInstant);
        } else {
            events = eventService.getEventsForTask(taskId);
        }

        return events.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * SSE endpoint for live event streaming.
     * 
     * <p>Clients connect and receive real-time events as they occur.
     * The connection remains open until the request completes or client disconnects.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }

        log.info("SSE client connected for request {}", requestId);

        // 30 minute timeout (long-running executions)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // Subscribe to events
        Runnable unsubscribe = eventService.subscribeToRequest(requestId, event -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.getId().toString())
                        .name(event.getEventType().name().toLowerCase())
                        .data(toResponse(event)));
            } catch (IOException e) {
                log.debug("SSE client disconnected: {}", e.getMessage());
                emitter.complete();
            }
        });

        // Send initial heartbeat
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Connected to request " + requestId));
        } catch (IOException e) {
            log.warn("Failed to send initial SSE event: {}", e.getMessage());
        }

        // Cleanup on completion or error
        emitter.onCompletion(() -> {
            log.info("SSE client completed for request {}", requestId);
            unsubscribe.run();
        });

        emitter.onTimeout(() -> {
            log.info("SSE client timeout for request {}", requestId);
            unsubscribe.run();
            emitter.complete();
        });

        emitter.onError(e -> {
            log.debug("SSE client error for request {}: {}", requestId, e.getMessage());
            unsubscribe.run();
        });

        // Send periodic heartbeat to keep connection alive
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Instant.now().toString()));
            } catch (Exception e) {
                heartbeat.shutdown();
            }
        }, 30, 30, TimeUnit.SECONDS);

        emitter.onCompletion(heartbeat::shutdown);
        emitter.onTimeout(heartbeat::shutdown);

        return emitter;
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
}
