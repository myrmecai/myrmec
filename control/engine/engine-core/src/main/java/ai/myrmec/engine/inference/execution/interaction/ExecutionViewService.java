// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 9 (plan 2026-10-03-session-interaction): the §4 read/view surface —
 * the ExecutionView snapshot, the durable event pages, and the §3.5
 * transactional cursor allocation + after-commit fan-out seam.
 *
 * <p><b>ExecutionView data sources (the brief/§4)</b>:</p>
 * <ul>
 *   <li>session_executions — lifecycle, the 035 control columns (control /
 *       accepted revisions, observed controlStateSequence, hold state,
 *       idleResumeAt), the effective interaction policy, the deadline,
 *       the pending interaction pointer, the public stream cursor</li>
 *   <li>execution_events — the Task-5-style PROGRESS identity/progress
 *       block (the SDK's immutable snapshot publisher rows) + budget
 *       limits/totals, plus the §4 durable cursors: {@code
 *       latestStreamSequence} = the execution row's stream_sequence,
 *       {@code earliestAvailableStreamSequence} = the LOWEST
 *       stream_sequence row still retained (the retention watermark; rows
 *       pruned from beneath it surface as the §4 replay-gap condition)</li>
 *   <li>interaction_usage — the budget subtotals + usageStatus (§3.1's
 *       accounting projection: accountedTokens/attribution/usageStatus)</li>
 *   <li>execution_control_requests — CONFIRMATION_REQUIRED rows are the
 *       pending cancel confirmations (§3.3)</li>
 * </ul>
 */
@Service
@Slf4j
public class ExecutionViewService {

    /** §4: the events page hard cap (max limit 500). */
    public static final int MAX_EVENT_PAGE_LIMIT = 500;

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final ExecutionEventRepository eventRepository;
    private final ExecutionInteractionRepository interactionRepository;
    private final ExecutionControlRequestRepository controlRequestRepository;
    private final ai.myrmec.engine.workflow.TaskAttemptRepository taskAttemptRepository;
    private final ai.myrmec.engine.node.NodeTransport nodeTransport;
    private final ExecutionStreamBroker streamBroker;
    private final ObjectMapper objectMapper;

    public ExecutionViewService(
            SessionExecutionRepository executionRepository,
            SessionRepository sessionRepository,
            ExecutionEventRepository eventRepository,
            ExecutionInteractionRepository interactionRepository,
            ExecutionControlRequestRepository controlRequestRepository,
            ai.myrmec.engine.workflow.TaskAttemptRepository taskAttemptRepository,
            ai.myrmec.engine.node.NodeTransport nodeTransport,
            @org.springframework.context.annotation.Lazy ExecutionStreamBroker streamBroker,
            ObjectMapper objectMapper) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.interactionRepository = interactionRepository;
        this.controlRequestRepository = controlRequestRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.nodeTransport = nodeTransport;
        this.streamBroker = streamBroker;
        this.objectMapper = objectMapper;
    }

    /** The §4 durable replay-gap event name. */
    public static final String REPLAY_GAP_EVENT = "execution.replay.gap";

    // =================================================================
    // GET /snapshot: the ExecutionView
    // =================================================================

    /**
     * §4 GET /snapshot. Chain-resolving (404 before any read); no row lock
     * (a consistent-enough read — the §4 snapshot is stale-at-view by
     * contract, the stream carries the live frames).
     */
    @Transactional(readOnly = true)
    public ExecutionView snapshot(ExecutionScope scope) {
        SessionExecution execution = resolveChain(scope);
        return viewOf(execution);
    }

    /**
     * The ExecutionView projection from the execution row + the §4 cursors.
     * Public so the stream controller can reuse it when a replay gap
     * requires a fresh snapshot inside the SSE body.
     */
    @Transactional(readOnly = true)
    public ExecutionView viewOf(SessionExecution execution) {
        Session session = sessionRepository.findById(execution.getSessionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Session",
                        execution.getSessionId()));

        ExecutionEvent progressRow = latestProgressRow(execution.getId());
        Map<String, Object> progress = progressRow == null ? null
                : progressBlockOf(progressRow);

        Map<String, Object> usage = execution.getInteractionUsage();
        if (usage == null) {
            usage = new LinkedHashMap<>();
        }

        Long latest = execution.getStreamSequence() == null ? 0L
                : execution.getStreamSequence();
        Long earliest = earliestAvailableSequence(execution);

        Map<String, Object> pendingCancel = pendingCancelConfirmation(execution.getId());

        return new ExecutionView(
                execution.getId(), session.getId(), execution.getDispatchId(),
                execution.getState() == null ? null : execution.getState().name(),
                execution.getHoldState(), execution.getIdleResumeAt(),
                execution.getControlRevision(), execution.getAcceptedControlRevision(),
                execution.getControlStateSequence(), execution.getHoldChangedAt(),
                execution.getDeadline(), execution.getStartedAt(),
                execution.getTerminalAt(),
                execution.getTerminalPayload(),
                execution.getInteractionPolicy(),
                usage, usageStatusOf(usage),
                progress,
                execution.getPendingInteractionId(),
                pendingCancel,
                latest, earliest);
    }

    /** The §4 usageStatus (KNOWN when any accounted settlement exists). */
    private static String usageStatusOf(Map<String, Object> usage) {
        Object status = usage.get("usageStatus");
        return status instanceof String s ? s : "UNKNOWN";
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** The newest progress/snapshot row (the Task-5 SDK publisher data). */
    private ExecutionEvent latestProgressRow(UUID executionId) {
        List<ExecutionEvent> rows = eventRepository
                .findByExecutionIdOrderByStreamSequenceAsc(executionId);
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> data = rows.get(i).getData();
            if (data != null && data.get("progressVersion") != null) {
                return rows.get(i);
            }
        }
        return null;
    }

    /** The §4 "immutable progress snapshot + version/time" block. */
    private Map<String, Object> progressBlockOf(ExecutionEvent row) {
        Map<String, Object> block = new LinkedHashMap<>();
        Map<String, Object> data = row.getData();
        for (String key : List.of("progressVersion", "progressCapturedAt",
                "holdState", "helperCallsCompleted", "verifierRejections",
                "budgetLimits", "budgetTotal", "usageStatus",
                "executionId", "dispatchId", "taskId", "attemptId",
                "attemptOrdinal", "stepId", "runId")) {
            if (data.containsKey(key)) {
                block.put(key, data.get(key));
            }
        }
        block.put("streamSequence", row.getStreamSequence());
        block.put("recordedAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return block;
    }

    /** §3.3: the pending cancel-confirmation (CONFIRMATION_REQUIRED rows). */
    private Map<String, Object> pendingCancelConfirmation(UUID executionId) {
        List<ExecutionControlRequest> rows = controlRequestRepository
                .findByExecutionIdAndStatusIn(executionId,
                        List.of(InteractionControlStatus.CONFIRMATION_REQUIRED));
        for (ExecutionControlRequest row : rows) {
            if ("CANCEL".equals(row.getAction())) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("controlRequestId", row.getId().toString());
                pending.put("status", row.getStatus() == null ? null : row.getStatus().name());
                pending.put("confirmationExpiresAt", iso(row.getConfirmationExpiresAt()));
                return pending;
            }
        }
        return null;
    }

    /**
     * §4: the earliest stream_sequence still retained for the execution
     * (rows PRUNED by retention leave a watermark ahead of 0 — the replay
     * gap check). Execution rows with NO retained events read 0.
     */
    private long earliestAvailableSequence(SessionExecution execution) {
        Optional<ExecutionEvent> lowest =
                eventRepository.findFirstByExecutionIdOrderByStreamSequenceAsc(
                        execution.getId());
        if (lowest.isEmpty()) {
            return 0;
        }
        Long lowestSeq = lowest.get().getStreamSequence();
        return lowestSeq == null ? 0 : lowestSeq;
    }

    // =================================================================
    // GET /events: the durable cursor page
    // =================================================================

    /**
     * §4 GET /events?afterSequence=&limit= — a stable durable cursor page
     * (max 500). afterSequence is EXCLUSIVE (the Last-Event-ID contract).
     * NEVER ordered by timestamp; strictly ascending stream_sequence.
     */
    @Transactional(readOnly = true)
    public ExecutionPage eventPage(ExecutionScope scope, long afterSequence, int limit) {
        if (afterSequence < 0) {
            afterSequence = 0;
        }
        int bounded = Math.max(1, Math.min(limit, MAX_EVENT_PAGE_LIMIT));
        resolveChain(scope);
        List<ExecutionEvent> rows = eventRepository
                .findByExecutionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
                        scope.executionId(), afterSequence);
        List<ExecutionEvent> page = new ArrayList<>();
        boolean hasMore = false;
        for (ExecutionEvent row : rows) {
            if (page.size() >= bounded) {
                hasMore = true;
                break;
            }
            page.add(row);
        }
        List<ExecutionStreamEvent> events = new ArrayList<>();
        long last = afterSequence;
        for (ExecutionEvent row : page) {
            events.add(sanitize(row));
            if (row.getStreamSequence() != null) {
                last = row.getStreamSequence();
            }
        }
        return new ExecutionPage(events, last, hasMore);
    }

    /** §3.5 sanitization: the wire frame from the durable row. */
    public ExecutionStreamEvent sanitize(ExecutionEvent row) {
        Map<String, Object> data = row.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(row.getData());
        // Defense-in-depth against a future durable delta row: the §4
        // contract — deltas have NO SSE id and NEVER ride the cursor.
        if (ExecutionStreamEvent.DELTA_NAME.equals(row.getMessage())) {
            return new ExecutionStreamEvent(null, ExecutionStreamEvent.DELTA_NAME, data);
        }
        return new ExecutionStreamEvent(row.getStreamSequence(), row.getMessage(), data);
    }

    /** The current durable high-water mark (execution row's cursor). */
    @Transactional(readOnly = true)
    public long currentStreamSequence(UUID executionId) {
        return executionRepository.findById(executionId)
                .map(e -> e.getStreamSequence() == null ? 0L : e.getStreamSequence())
                .orElse(0L);
    }

    /** §4: the retention watermark — the earliest retained cursor. */
    @Transactional(readOnly = true)
    public long earliestAvailableStreamSequence(UUID executionId) {
        return earliestAvailableSequence(executionRepository.findById(executionId)
                .orElseThrow(() -> ResourceNotFoundException.of("Execution", executionId)));
    }

    // =================================================================
    // §3.5: transactional cursor allocation + after-commit fan-out
    // =================================================================

    /**
     * §3.5: allocate the public stream event. (Review Fix 4a) the
     * execution is (RE-)RESOLVED via {@code findWithLockById} INSIDE this
     * call's transaction: a caller holding the row lock re-enters the
     * same lock harmlessly, while an UNGUARDED caller still serializes
     * against the locked writers instead of racing the cursor.
     * Self-sufficient transaction (REQUIRED) so a lock-free caller gets
     * an atomic cursor+event commit. Returns the allocated
     * stream_sequence.
     */
    @Transactional
    public long allocateEvent(UUID executionId, String eventName,
                              Map<String, Object> data) {
        SessionExecution execution = executionRepository.findWithLockById(executionId)
                .orElseThrow(() -> ResourceNotFoundException.of("Execution", executionId));
        return allocate(execution, eventName, data);
    }

    /**
     * §3.5: the allocation kernel (caller HOLDS the row lock). Allocates
     * the next sequence, persists the durable event row + the execution
     * cursor advance, and registers AFTER-COMMIT fan-out (the §4
     * "wakeup hint" — durable-cursor catch-up remains the repair path).
     */
    public long allocate(SessionExecution execution, String eventName,
                         Map<String, Object> data) {
        long sequence = (execution.getStreamSequence() == null ? 0L
                : execution.getStreamSequence()) + 1;
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(ai.myrmec.engine.workflow.EventType.ORCHESTRATION);
        event.setExecutionId(execution.getId());
        event.setStreamSequence(sequence);
        event.setKind("WORKFLOW");
        event.setSource(ai.myrmec.engine.workflow.LogSource.AGENT);
        event.setMessage(eventName);
        event.setData(data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data));
        eventRepository.save(event);
        execution.setStreamSequence(sequence);
        executionRepository.save(execution);
        fanOutAfterCommit(execution.getId(), sequence);
        return sequence;
    }

    /**
     * AFTER-COMMIT node fan-out: a WAKEUP HINT (§3.5) — two arms (review
     * Fix 3a): (1) the LOCAL broker catches up its own subscribers from
     * the committed rows; (2) the {@link ai.myrmec.engine.node.NodeTransport}
     * execution arm relays the typed envelope to PEER nodes whose
     * subscriber set is non-empty (the remote arm routes to
     * {@code deliverRemoteFrameRelay}, which re-enters the catch-up from
     * the committed cursor — the relay frame bytes are never the durable
     * delivery authority). Never throws: wakeup-hint failures are
     * repairable by the catch-up sweep. Registered only inside a live
     * transaction (read-only probes bypass registration).
     */
    private void fanOutAfterCommit(UUID executionId, long sequence) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            streamBroker.notifyCommitted(executionId, sequence);
                        } catch (Exception e) {
                            // Wakeup-hint failures are repairable by the
                            // catch-up scheduler — never break the caller.
                            log.debug("Post-commit stream fan-out for execution {} "
                                    + "sequence {} failed (durable catch-up repairs): {}",
                                    executionId, sequence, e.getMessage());
                        }
                        try {
                            String envelope = envelopeForRelay(executionId, sequence);
                            if (envelope != null) {
                                nodeTransport.publishExecutionEvent(executionId, envelope);
                            }
                        } catch (Exception e) {
                            // Peer wakeup failures are equally repairable —
                            // the sweep catches peers up from the durable
                            // cursor's committed rows.
                            log.debug("Post-commit peer relay for execution {} "
                                    + "sequence {} failed (durable catch-up repairs): {}",
                                    executionId, sequence, e.getMessage());
                        }
                    }
                });
    }

    /**
     * The relay envelope for the committed sequence: the committed row's
     * OWN bytes ({@code {streamSequence, name, payload}}) — peers re-fetch
     * the durable row anyway, so the hint only needs the identity.
     */
    private String envelopeForRelay(UUID executionId, long sequence) {
        ai.myrmec.engine.workflow.ExecutionEvent row = eventRepository
                .findByExecutionIdAndStreamSequence(executionId, sequence)
                .orElse(null);
        if (row == null) {
            return null;
        }
        ExecutionStreamEvent event = sanitize(row);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("streamSequence", event.streamSequence());
        envelope.put("name", event.name());
        envelope.put("executionId", executionId.toString());
        envelope.put("payload", event.payload());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return null;
        }
    }

    // =================================================================
    // Chain resolution (the §4 ownership chain — every read 404s first)
    // =================================================================

    /** §4 ownership chain; a mismatch anywhere is a 404 BEFORE any read. */
    @Transactional(readOnly = true)
    public SessionExecution resolveChain(ExecutionScope scope) {
        SessionExecution execution = executionRepository.findById(scope.executionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Execution",
                        scope.executionId()));
        Session session = sessionRepository.findById(execution.getSessionId())
                .orElseThrow(() -> chainNotFound(scope));
        if (!scope.projectId().equals(session.getProjectId())) {
            throw chainNotFound(scope);
        }
        if (!"WORKFLOW".equals(session.getServiceType())
                || !scope.requestId().equals(session.getRefId())) {
            throw chainNotFound(scope);
        }
        var attempt = taskAttemptRepository.findById(scope.attemptId())
                .orElseThrow(() -> chainNotFound(scope));
        if (attempt.getTask() == null || !scope.taskId().equals(attempt.getTask().getId())) {
            throw chainNotFound(scope);
        }
        var task = attempt.getTask();
        var request = task.getRequest();
        if (request == null || !scope.requestId().equals(request.getId())) {
            throw chainNotFound(scope);
        }
        var workflow = request.getWorkflow();
        if (workflow == null || !scope.workflowId().equals(workflow.getId())) {
            throw chainNotFound(scope);
        }
        return execution;
    }

    private static ResourceNotFoundException chainNotFound(ExecutionScope scope) {
        return ResourceNotFoundException.of("Execution", scope.executionId());
    }

    // =================================================================
    // §3.5 swept-failure event allocation for the retention sweeper
    // =================================================================

    /**
     * The §3.5 swept-failure event DATA kernel. NOTE (review Fix 3b):
     * the sweeper calls {@link #allocate} DIRECTLY (below) with this same
     * data shape — this record-shape contract lives here as the single
     * §3.5 interaction-event vocabulary; the sweeper holds no duplicate
     * write kernel (duplicated kernels were deleted — one allocate, one
     * fan-out, one wakeup relay).
     */
    public static Map<String, Object> sweptFailureData(
            ExecutionInteraction interaction, String errorCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "interaction");
        data.put("interactionId", interaction.getId().toString());
        data.put("ordinal", interaction.getOrdinal());
        data.put("outcome", InteractionStatus.FAILED.name());
        data.put("errorCode", errorCode);
        data.put("swept", true);
        data.put("usageStatus", interaction.getUsageStatus());
        return data;
    }

    // =================================================================
    // Records
    // =================================================================

    /**
     * §4 GET /events response: one cursor-ordered page.
     *
     * @param events       the sanitized frames (ascending streamSequence)
     * @param nextSequence the CLIENT's next afterSequence (the page's last
     *                     frame's sequence; the caller's afterSequence when empty)
     * @param hasMore      true when the page hit its limit with rows left
     */
    public record ExecutionPage(List<ExecutionStreamEvent> events,
                                long nextSequence, boolean hasMore) {

        public long lastSequence() {
            return nextSequence;
        }
    }

    /**
     * §4 ExecutionView (camelCase JSON). One orchestration execution's
     * consistent read state + cursors.
     */
    public record ExecutionView(
            UUID executionId,
            UUID sessionId,
            UUID dispatchId,
            String state,
            String holdState,
            Instant idleResumeAt,
            Long controlRevision,
            Long acceptedControlRevision,
            Long controlStateSequence,
            Instant holdChangedAt,
            Instant deadline,
            Instant startedAt,
            Instant terminalAt,
            Map<String, Object> terminalPayload,
            Map<String, Object> interactionPolicy,
            Map<String, Object> interactionUsage,
            String usageStatus,
            Map<String, Object> progress,
            UUID pendingInteractionId,
            Map<String, Object> pendingCancelConfirmation,
            Long latestStreamSequence,
            Long earliestAvailableStreamSequence) {
    }

    // =================================================================
    // Wire helpers (the relay-frame parse)
    // =================================================================

    /** The shared parse mapper (thread-safe; read-only usage). */
    private static final ObjectMapper PARSE_MAPPER = new ObjectMapper();

    /**
     * Parse an already-validated relay frame envelope
     * ({@code {streamSequence, name, payload}}) — the node-relay contract.
     */
    public static ExecutionStreamEvent parseEnvelope(String jsonFrame) {
        try {
            Map<String, Object> envelope = PARSE_MAPPER.readValue(jsonFrame,
                    new TypeReference<Map<String, Object>>() { });
            Long sequence = envelope.get("streamSequence") instanceof Number n ? n.longValue() : null;
            String name = envelope.get("name") instanceof String s ? s : null;
            Map<String, Object> payload = envelope.get("payload") instanceof Map<?, ?> p
                    ? new LinkedHashMap<>() : null;
            if (payload != null && envelope.get("payload") instanceof Map<?, ?> nested) {
                for (Map.Entry<?, ?> entry : nested.entrySet()) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return new ExecutionStreamEvent(sequence, name, payload);
        } catch (Exception e) {
            return null;
        }
    }
}
