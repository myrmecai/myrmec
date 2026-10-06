// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Task 9 (plan §4 routes, ownership chain
 * {@code /api/v1/projects/{projectId}/workflows/{workflowId}/requests/
 * {requestId}/tasks/{taskId}/attempts/{attemptId}/executions/{executionId}}):
 * the read/view surface — GET /snapshot (the ExecutionView), GET /events
 * (the durable cursor page), GET /stream (the replay/live/gap SSE).
 *
 * <p>GET /interactions is Task 8's {@link ExecutionInteractionController}
 * (NOT re-implemented here). POSTs are Task 6/8's; this controller is
 * READ-only — the §4 role gate: VIEWER-equivalent may read; EDITOR-
 * equivalent is implied ({@code canView}).</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows/{workflowId}/requests"
        + "/{requestId}/tasks/{taskId}/attempts/{attemptId}"
        + "/executions/{executionId}")
@Slf4j
@RequiredArgsConstructor
public class ExecutionViewController {

    /** 30 minutes — matches the conversation/execution SSE family. */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ExecutionViewService viewService;
    private final ExecutionStreamBroker streamBroker;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // =================================================================
    // GET /snapshot
    // =================================================================

    /**
     * §4 GET /snapshot → 200 ExecutionView: a consistent read of the
     * execution state + the §4 cursors ({@code latestStreamSequence} for
     * the client's subscribe-after-snapshot pattern,
     * {@code earliestAvailableStreamSequence} for the replay-gap check).
     */
    @GetMapping("/snapshot")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ExecutionViewService.ExecutionView snapshot(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId) {
        return viewService.snapshot(
                scopeOf(projectId, workflowId, requestId, taskId, attemptId, executionId));
    }

    // =================================================================
    // GET /events
    // =================================================================

    /**
     * §4 GET /events?afterSequence=&limit= — the stable durable cursor
     * page (max 500), strictly ascending by stream_sequence (NOT
     * timestamp order), afterSequence EXCLUSIVE.
     */
    @GetMapping("/events")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public Map<String, Object> events(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @RequestParam(name = "afterSequence", defaultValue = "0") long afterSequence,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        ExecutionViewService.ExecutionPage page = viewService.eventPage(
                scopeOf(projectId, workflowId, requestId, taskId, attemptId, executionId),
                afterSequence, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", page.events());
        body.put("nextSequence", page.nextSequence());
        body.put("hasMore", page.hasMore());
        return body;
    }

    // =================================================================
    // GET /stream (SSE)
    // =================================================================

    /**
     * §4 GET /stream?afterSequence= — the replay/live SSE with
     * Last-Event-ID support ({@code Last-Event-ID} header wins over the
     * query when present, per the SSE spec).
     *
     * <p>The §4 subscriber algorithm (plan-verbatim) runs here:</p>
     * <ol>
     *   <li>authorize (the §4 ownership chain + @PreAuthorize)</li>
     *   <li>subscribe and buffer (register the sink in the broker)</li>
     *   <li>read the durable high-water + replay rows (the broker's
     *       cursor-ordered catch-up from the client's cursor)</li>
     *   <li>send rows ordered → drain buffered IDs → live</li>
     *   <li>reconnect with Last-Event-ID → back to step 3 with the
     *       client's cursor</li>
     * </ol>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public SseEmitter stream(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @RequestParam(name = "afterSequence", required = false) Long afterSequence,
            @RequestParam(name = "token", required = false) String token,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "Last-Event-ID",
                    required = false) String lastEventId) {
        var scope = scopeOf(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
        // The §4 ownership chain resolves 404 BEFORE the stream opens.
        var execution = viewService.resolveChain(scope);

        long cursor = resolveCursor(afterSequence, lastEventId);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseExecutionSubscriber subscriber = new SseExecutionSubscriber(emitter);
        ExecutionStreamBroker broker = streamBroker;

        // Connected: NO replay cursor (§4) — the id-less first frame.
        try {
            Map<String, Object> connected = new LinkedHashMap<>();
            connected.put("executionId", executionId.toString());
            connected.put("connectedAt", Instant.now().toString());
            emitter.send(SseEmitter.event().name("connected")
                    .data(ExecutionStreamEvent.connected(connected)
                            .toJson(viewServiceJsonMapper())));
        } catch (IOException e) {
            log.debug("Execution SSE client disconnected during connect frame: {}",
                    e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }

        // §4 ALGORITHM: subscribe (buffer) → read high-water + replay.
        broker.subscribe(scope.executionId(), subscriber, cursor);
        try {
            broker.replayAndDrain(scope.executionId(), subscriber, cursor);
        } catch (RuntimeException e) {
            log.debug("Execution stream replay for {} failed: {}",
                    scope.executionId(), e.getMessage());
        }

        emitter.onCompletion(() -> {
            subscriber.markClosed();
            broker.unsubscribe(scope.executionId(), subscriber);
        });
        emitter.onTimeout(() -> {
            subscriber.markClosed();
            broker.unsubscribe(scope.executionId(), subscriber);
            emitter.complete();
        });
        emitter.onError(e -> {
            subscriber.markClosed();
            broker.unsubscribe(scope.executionId(), subscriber);
        });

        log.debug("Execution SSE subscriber attached to execution {} (cursor {})",
                scope.executionId(), cursor);
        return emitter;
    }

    /** Last-Event-ID header wins over the query parameter. */
    private static long resolveCursor(Long afterSequence, String lastEventId) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException e) {
                throw BadRequestException.forField("lastEventId", "INVALID_FORMAT",
                        "Last-Event-ID must be a decimal stream sequence.");
            }
        }
        return afterSequence == null ? 0 : Math.max(0, afterSequence);
    }

    /** The controller-injected mapper (from the view service's bean). */
    private com.fasterxml.jackson.databind.ObjectMapper viewServiceJsonMapper() {
        return objectMapper;
    }

    private static ExecutionScope scopeOf(UUID projectId, UUID workflowId,
                                          UUID requestId, UUID taskId, UUID attemptId,
                                          UUID executionId) {
        return new ExecutionScope(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
    }

    // =================================================================
    // Section-4 error shape mapping (mirrors Task 8's controller)
    // =================================================================

    @ExceptionHandler({ExecutionStateException.class, ResourceInUseException.class})
    public ResponseEntity<ErrorResponse> handleExecutionState(Exception ex) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resourceType", "Execution");
        detail.put("blocking", true);
        detail.put("count", 1);
        String reasonCode = null;
        if (ex instanceof ExecutionStateException state) {
            reasonCode = state.getReasonCode();
        } else if (ex instanceof ResourceInUseException inUse && !inUse.getDetails().isEmpty()) {
            reasonCode = inUse.getDetails().get(0).getResourceType();
        }
        detail.put("reasonCode", reasonCode == null ? "" : reasonCode);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.builder()
                        .errorCode("RESOURCE_IN_USE")
                        .message(ex.getMessage())
                        .details(List.of(detail))
                        .build());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_CODE", ex.getMessage()));
    }

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        if (ex instanceof BadRequestException bad && bad.hasDetails()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.validation(bad.getDetails()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.resourceNotFound(ex.getResourceType(),
                        ex.getIdentifier()));
    }
}