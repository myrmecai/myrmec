// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import ai.myrmec.engine.websocket.host.payload.SessionCloseReason;
import ai.myrmec.engine.workflow.OrchestrationOutcomeService;
import ai.myrmec.engine.workflow.TaskAttemptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §12.2 {@code execution.start} timeout (engine half): an execution that
 * stays STARTING past {@code myrmec.host.execution-start-timeout-seconds}
 * (the same window {@code host.opened.streamLimits} exposes to hosts for
 * accept discipline) is dispatch-lost — the host neither accepted nor
 * rejected inside the window. The sweeper transitions it to FAILED with the
 * §14 {@code DISPATCH_TIMEOUT} error code and applies the §12.2 session
 * policy: a conversation session stays open (its next turn re-dispatches on
 * a fresh execution), a workflow session closes with the execution terminal
 * (the one-shot task session returns its slot).
 *
 * <p>Idempotent by construction: only non-terminal STARTING rows inside the
 * window are touched; a late accept racing the sweep is rejected by the
 * state machine (accept requires STARTING) and the outcome dedup substrate
 * ({@code terminal_message_id}) absorbs any conflicting late terminal frame.
 */
@Component
@Slf4j
public class ExecutionStartTimeoutSweeper {

    /** §14 addition: the dispatch window lapsed before execution.accept. */
    public static final String ERROR_CODE_DISPATCH_TIMEOUT = "DISPATCH_TIMEOUT";

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final SessionAllocator sessionAllocator;
    private final ExecutionRegistry executionRegistry;
    private final TaskAttemptService taskAttemptService;
    private final OrchestrationOutcomeService orchestrationOutcomeService;
    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final int executionStartTimeoutSeconds;

    public ExecutionStartTimeoutSweeper(
            SessionExecutionRepository executionRepository,
            SessionRepository sessionRepository,
            SessionAllocator sessionAllocator,
            ExecutionRegistry executionRegistry,
            TaskAttemptService taskAttemptService,
            OrchestrationOutcomeService orchestrationOutcomeService,
            HostControlWebSocketHandler hostControlWebSocketHandler,
            ObjectMapper objectMapper,
            @Value("${myrmec.host.execution-start-sweep.enabled:true}") boolean enabled,
            @Value("${myrmec.host.execution-start-timeout-seconds:300}")
            int executionStartTimeoutSeconds) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.sessionAllocator = sessionAllocator;
        this.executionRegistry = executionRegistry;
        this.taskAttemptService = taskAttemptService;
        this.orchestrationOutcomeService = orchestrationOutcomeService;
        this.hostControlWebSocketHandler = hostControlWebSocketHandler;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.executionStartTimeoutSeconds = executionStartTimeoutSeconds;
    }

    /** §12.2 scheduled dispatch-timeout sweep — disabled in e2e like the reaper. */
    @Scheduled(fixedDelayString = "${myrmec.host.execution-start-sweep.interval-ms:15000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        int swept = expireTimedOutStarts(Instant.now());
        if (swept > 0) {
            log.info("Execution start sweep: {} dispatch-lost execution(s)", swept);
        }
    }

    /**
     * Transition every STARTING execution created before {@code now - window}
     * to FAILED (DISPATCH_TIMEOUT) and apply the session policy per kind.
     *
     * @return the number of executions swept
     */
    @Transactional
    public int expireTimedOutStarts(Instant now) {
        Instant cutoff = now.minusSeconds(Math.max(1, executionStartTimeoutSeconds));
        var stale = executionRepository.findByStateAndCreatedAtBefore(
                SessionExecution.State.STARTING, cutoff);
        for (SessionExecution execution : stale) {
            sweepOne(execution, now);
        }
        return stale.size();
    }

    private void sweepOne(SessionExecution execution, Instant now) {
        boolean workflow = "WORKFLOW".equals(execution.getServiceType());
        UUID sessionId = execution.getSessionId();

        // §11.2: STARTING → FAILED (the timeout arm of the execution FSM).
        execution.setState(SessionExecution.State.FAILED);
        execution.setTerminalMessageId("sweeper-dispatch-timeout-" + execution.getId());
        execution.setTerminalPayload(dispatchTimeoutPayload());
        execution.setTerminalAt(now);
        executionRepository.save(execution);

        if (workflow) {
            // §12.2 workflow policy: the dispatch fails with the execution —
            // ordinary steps through the task-attempt sink (retry policy
            // honoured), ORCHESTRATOR attempts through the orchestration
            // outcome sink (same FAILED tuple the outcome path applies).
            UUID attemptId = attemptIdOf(execution);
            if (execution.getDispatchId() != null) {
                try {
                    orchestrationOutcomeService.applyResult(
                            execution.getDispatchId(), execution.getId(),
                            resultDigestOf(execution),
                            OrchestrationOutcomeService.OutcomeStatus.FAILED,
                            OrchestrationOutcomeService.RetryDisposition.RETRYABLE,
                            ERROR_CODE_DISPATCH_TIMEOUT,
                            orchestrationTimeoutPayload(execution));
                } catch (Exception e) {
                    log.warn("DISPATCH_TIMEOUT outcome for dispatch {} rejected: {}",
                            execution.getDispatchId(), e.getMessage());
                    taskAttemptService.completeFailed(execution.getDispatchId(),
                            "Execution start timed out after "
                                    + executionStartTimeoutSeconds + "s",
                            ERROR_CODE_DISPATCH_TIMEOUT);
                }
            } else if (attemptId != null) {
                taskAttemptService.completeFailed(attemptId,
                        "Execution start timed out after "
                                + executionStartTimeoutSeconds + "s (no execution.accept)",
                        ERROR_CODE_DISPATCH_TIMEOUT);
            } else {
                log.warn("Dispatch-lost workflow execution {} has no task sink — session only",
                        execution.getId());
            }
            // §12.2: a workflow session is one-shot — close with the terminal
            // execution so the slot returns (mirrors the terminal-frame path).
            closeSession(sessionId, SessionCloseReason.INITIALIZATION_FAILED);
        } else {
            // §12.2 conversation policy: keep the session open — the next
            // user turn re-dispatches on a fresh execution.
            log.info("Conversation execution {} dispatch-lost after {}s — session {} stays open",
                    execution.getId(), executionStartTimeoutSeconds, sessionId);
        }
    }

    private void closeSession(UUID sessionId, String reasonCode) {
        sessionAllocator.close(sessionId, reasonCode);
        hostControlWebSocketHandler.sendSessionClose(sessionId, reasonCode);
    }

    /** The recorded terminal outcome as a §8.5 error-shaped map. */
    private Map<String, Object> dispatchTimeoutPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", Map.of(
                "code", ERROR_CODE_DISPATCH_TIMEOUT,
                "message", "Execution start timed out before execution.accept",
                "category", "ENGINE",
                "retryable", true));
        return payload;
    }

    /** The structured orchestration result for the FAILED outcome tuple. */
    private Map<String, Object> orchestrationTimeoutPayload(SessionExecution execution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.getId().toString());
        payload.put("summary", "Execution start timed out before execution.accept");
        payload.put("error", Map.of(
                "code", ERROR_CODE_DISPATCH_TIMEOUT,
                "retryable", true));
        return payload;
    }

    /** Deterministic digest for the engine-authored timeout result. */
    private String resultDigestOf(SessionExecution execution) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(orchestrationTimeoutPayload(execution));
            return ai.myrmec.engine.workflow.OrchestrationIds.sha256Hex(canonical);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /** The task id an ordinary workflow execution serves (requestId = task id). */
    private UUID attemptIdOf(SessionExecution execution) {
        if (execution.getRequestId() == null) {
            return null;
        }
        try {
            return taskAttemptService.findCurrentAttemptId(
                    UUID.fromString(execution.getRequestId())).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}