// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 6 (plan section 4 routes, base ownership chain
 * {@code /api/v1/projects/{projectId}/workflows/{workflowId}/requests/
 * {requestId}/tasks/{taskId}/attempts/{attemptId}/executions/{executionId}}):
 * the three control routes — POST /controls (HOLD/CONTINUE, 202),
 * POST /cancel (confirmed, 202), POST /control-requests/{controlRequestId}/
 * decision (CONFIRM/DECLINE, 200) — plus Task 8's §4 interaction routes:
 * GET /interactions (the redacted transcript page, ordinal order,
 * max limit 100) and POST /interactions (chat admission, 202; one pending
 * interaction; identical retries return the SAME record). SSE/snapshot
 * remain Tasks 8/9 (task-boundary NOTE).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows/{workflowId}/requests"
        + "/{requestId}/tasks/{taskId}/attempts/{attemptId}"
        + "/executions/{executionId}")
@Slf4j
@RequiredArgsConstructor
public class ExecutionInteractionController {

    private final ExecutionControlService controlService;
    private final ExecutionInteractionService interactionService;

    // =================================================================
    // Task 8 §4 interaction routes (GET transcript page + POST admission)
    // =================================================================

    /**
     * §4 GET /interactions?afterOrdinal=0&limit=50 — the redacted
     * transcript page in ordinal order (max limit 100; retention-aware —
     * rows past retention carry the CONTENT_EXPIRED markers the sweeper
     * wrote). Requires VIEWER-equivalent read access (§4).
     */
    @GetMapping("/interactions")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ResponseEntity<Map<String, Object>> interactions(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @RequestParam(name = "afterOrdinal", defaultValue = "0") long afterOrdinal,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        // §4: max limit 100, bounded above 0.
        int bounded = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> items = new ArrayList<>();
        // The service returns AT MOST limit+1 rows so an exact-fit page
        // reports hasMore=false honestly; trim the lookahead row off.
        List<ExecutionInteraction> rows = interactionService
                .transcriptPage(scopeOf(projectId, workflowId, requestId, taskId,
                        attemptId, executionId), afterOrdinal, bounded);
        boolean hasMore = rows.size() > bounded;
        List<ExecutionInteraction> page = hasMore
                ? rows.subList(0, bounded) : rows;
        for (ExecutionInteraction row : page) {
            items.add(InteractionTranscriptPage.itemOf(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("hasMore", hasMore);
        return ResponseEntity.ok(body);
    }

    /**
     * §4 POST /interactions {clientRequestId, text} → 202
     * {interactionId, ordinal, status, responseDeadline}. One pending
     * interaction; identical retries return the same record; the engine
     * dispatches the interaction command post-commit (never a host
     * receipt in the 202).
     */
    @PostMapping("/interactions")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<InteractionAdmission> interact(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal ai.myrmec.engine.user.UserPrincipal principal,
            @RequestBody InteractionChatRequest request) {
        if (request == null || request.clientRequestId() == null) {
            throw BadRequestException.forField("clientRequestId", "REQUIRED",
                    "clientRequestId is required.");
        }
        if (request.text() == null || request.text().isBlank()) {
            throw BadRequestException.forField("text", "REQUIRED",
                    "Text is required and may not be blank.");
        }
        ExecutionScope scope = scopeOf(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
        InteractionAdmission admission = interactionService.admit(scope, principal,
                request.clientRequestId(), request.text());
        return ResponseEntity.accepted().body(admission);
    }

    // =================================================================
    // Task 6 control routes
    // =================================================================

    @PostMapping("/controls")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<ControlReceipt> control(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal ai.myrmec.engine.user.UserPrincipal principal,
            @RequestBody ControlRequest request) {
        ExecutionScope scope = scopeOf(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
        ControlReceipt receipt = controlService.control(scope, principal, request);
        return ResponseEntity.accepted().body(receipt);
    }

    @PostMapping("/cancel")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<ControlReceipt> cancel(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @AuthenticationPrincipal ai.myrmec.engine.user.UserPrincipal principal,
            @RequestBody ConfirmedCancelRequest request) {
        ExecutionScope scope = scopeOf(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
        ControlReceipt receipt = controlService.cancel(scope, principal, request);
        return ResponseEntity.accepted().body(receipt);
    }

    @PostMapping("/control-requests/{controlRequestId}/decision")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<ProposalReceipt> decide(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId,
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId,
            @PathVariable UUID executionId,
            @PathVariable UUID controlRequestId,
            @AuthenticationPrincipal ai.myrmec.engine.user.UserPrincipal principal,
            @RequestBody Decision decision) {
        ExecutionScope scope = scopeOf(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
        ProposalReceipt receipt = controlService.decide(scope, controlRequestId,
                principal, decision);
        // §4: an EXPIRED disposition is the 409 CONFIRMATION_EXPIRED state
        // conflict — the §4 standard error envelope (errorCode RESOURCE_IN_USE
        // + details[0].reasonCode CONFIRMATION_EXPIRED), the SAME canonical
        // shape the terminal handler emits. The durable row was settled
        // EXPIRED in the decision transaction; the envelope tells the client
        // to refresh instead of blindly retrying.
        if ("EXPIRED".equals(receipt.status())) {
            throw new ExecutionStateException("CONFIRMATION_EXPIRED",
                    "The cancel confirmation window expired — refresh the execution view "
                            + "instead of retrying the decision");
        }
        return ResponseEntity.ok(receipt);
    }

    // =================================================================
    // Section-4 error-shape mapping (route-local handlers — the §4 codes
    // with details.reasonCode are control-route-specific)
    // =================================================================

    private static ExecutionScope scopeOf(UUID projectId, UUID workflowId, UUID requestId,
                                          UUID taskId, UUID attemptId, UUID executionId) {
        return new ExecutionScope(projectId, workflowId, requestId, taskId,
                attemptId, executionId);
    }

    /** 409 RESOURCE_IN_USE with details.reasonCode (§4: state conflicts).
     * The service's terminal/state rejections carry the reasonCode in the
     * first detail's resourceType (EXECUTION_TERMINAL etc.). */
    @ExceptionHandler({ExecutionStateException.class,
            ai.myrmec.engine._system.exception.ResourceInUseException.class})
    public ResponseEntity<ErrorResponse> handleExecutionState(Exception ex) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resourceType", "Execution");
        detail.put("blocking", true);
        detail.put("count", 1);
        String reasonCode = null;
        if (ex instanceof ExecutionStateException state) {
            reasonCode = state.getReasonCode();
        } else if (ex instanceof ai.myrmec.engine._system.exception.ResourceInUseException inUse
                && !inUse.getDetails().isEmpty()) {
            // The reasonCode rides the detail's resourceType slot.
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

    /** 409 DUPLICATE_CODE (§4: same clientRequestId, different bytes). */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_CODE", ex.getMessage()));
    }

    /** 400 VALIDATION_ERROR (§4: bytes/empty text/invalid action/confirmed:false). */
    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        if (ex instanceof BadRequestException bad && bad.hasDetails()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.validation(bad.getDetails()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", ex.getMessage()));
    }

    /**
     * §22.6: the engine's OWN outbound secret scan refused the admission —
     * fail-closed 400 VALIDATION_ERROR with the CAPTURE_BLOCKED field error
     * (the standard error shape; never a 500).
     */
    @ExceptionHandler(InteractionCaptureBlockedException.class)
    public ResponseEntity<ErrorResponse> handleCaptureBlocked(
            InteractionCaptureBlockedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation(List.of(
                        ai.myrmec.engine._system.exception.ValidationDetail.of("text",
                                "CAPTURE_BLOCKED",
                                "The message failed the outbound content inspection."))));
    }

    /** 404 RESOURCE_NOT_FOUND (§4: ownership-chain mismatch / unknown proposal). */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.resourceNotFound(ex.getResourceType(),
                        ex.getIdentifier()));
    }

    /** §22.7: the resolved-disposition contract mirror for the UI pollers. */
    static ExecutionControlRequestResolvedPayload toResolved(ProposalReceipt receipt,
                                                             ExecutionScope scope) {
        return new ExecutionControlRequestResolvedPayload(
                scope.executionId(), null, null, receipt.controlRequestId(),
                receipt.resolutionRevision(),
                ExecutionControlRequestResolvedPayload.Status.valueOf(receipt.status()),
                receipt.expiresAt(), receipt.commandMessageId(),
                receipt.controlRevision(),
                receipt.errorCode() == null ? null
                        : ExecutionControlRequestResolvedPayload.ErrorCode.valueOf(receipt.errorCode()));
    }
}
