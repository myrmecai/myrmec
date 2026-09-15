// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.*;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service responsible for dispatching pending tasks to available agents.
 * Runs on a scheduled interval to match tasks with agents.
 *
 * <p><b>One-way unified path.</b> Every dispatch &mdash; an ordinary INFERENCE
 * step and an ORCHESTRATOR step alike &mdash; allocates a session on the
 * capacity-selected host ({@link SessionAllocator#offer}), offers it
 * ({@code session.offer}) and parks the assembled dispatch in
 * {@link PendingTaskDispatches}. The host's {@code session.accept} answer ships
 * {@code session.open} and its {@code session.opened} answer ships
 * {@code execution.start} (protocol &sect;7.3/&sect;7.4: no execution may start
 * before the session is ACTIVE). The legacy {@code session.open} +
 * {@code inference.assign} frames from this service are gone.</p>
 *
 * <p>The two families differ only in what they carry: an ORCHESTRATOR step
 * installs its complete &sect;16.2 self-contained assignment at
 * {@code session.open} (the assignment IS that session's context) and
 * {@code execution.start} references the stored bytes by
 * {@code dispatchId/attemptId/assignmentDigest}; an ordinary step ships its
 * &sect;8.1 transcript on {@code execution.start}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatcherService {

    /** Protocol &sect;3.1 session kind for every workflow task session. */
    public static final String SESSION_KIND_TASK = "ORCHESTRATION_TASK";

    /** Workflow task sessions are WORKFLOW-service sessions. */
    public static final String SERVICE_TYPE_WORKFLOW = "WORKFLOW";

    /** Default per-attempt timeout when the step declares none. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    // §3.7: host candidates come from the capacity-based selector; the
    // profile-keyed AgentHostRepository lookup is gone.
    private final HostSelectionService hostSelectionService;
    private final AgentRepository agentInstanceRepository;
    private final AgentHostRepository agentHostRepository;
    private final AgentHostInstanceRepository hostInstanceRepository;
    private final SessionAllocator sessionAllocator;
    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final PendingTaskDispatches pendingTaskDispatches;
    private final TaskAttemptService taskAttemptService;
    private final TaskAttemptRepository attemptRepository;
    // Feature 10 (§16.2/§16.3/§16.4): the orchestration dispatch pipeline.
    private final OrchestrationAffinityResolver affinityResolver;
    private final OrchestrationRunService orchestrationRunService;
    private final OrchestrationAssignmentAssembler assignmentAssembler;
    // §16.4(4-8): availability throttling + terminal loss persistence.
    private final OrchestrationRunRepository orchestrationRunRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final OrchestrationDispatchRepository dispatchRepository;

    /**
     * Dispatch pending tasks to available agents.
     * Runs every 2 seconds.
     */
    @Scheduled(fixedRate = 2000)
    @Transactional
    public void dispatchPendingTasks() {
        // Find all pending tasks
        List<WorkflowTask> pendingTasks = taskRepository.findByStatus(TaskStatus.PENDING);
        
        if (pendingTasks.isEmpty()) {
            return;
        }
        
        log.debug("Found {} pending tasks to dispatch", pendingTasks.size());
        
        for (WorkflowTask task : pendingTasks) {
            try {
                // Skip tasks belonging to a cancelled/finished/paused request.
                RequestStatus reqStatus = task.getRequest().getStatus();
                if (reqStatus == RequestStatus.CANCELLED
                        || reqStatus == RequestStatus.COMPLETED
                        || reqStatus == RequestStatus.FAILED
                        || reqStatus == RequestStatus.PAUSED) {
                    continue;
                }
                // Phase 10 #71 — honour rate-limit backoff.
                if (task.getNextEligibleAt() != null
                        && task.getNextEligibleAt().isAfter(Instant.now())) {
                    continue;
                }
                // J3: Pause gate BEFORE — if the task has pauseMode BEFORE or BOTH
                // and hasn't been paused yet, transition it to PAUSED before dispatch.
                if (shouldPauseBefore(task)) {
                    pauseTaskBefore(task);
                    continue;
                }
                dispatchTask(task);
            } catch (Exception e) {
                log.error("Failed to dispatch task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    /**
     * Check if a task should be paused before dispatch.
     */
    private boolean shouldPauseBefore(WorkflowTask task) {
        if (task.getPauseMode() == null) return false;
        return (task.getPauseMode() == PauseMode.BEFORE || task.getPauseMode() == PauseMode.BOTH)
                && "NONE".equals(task.getPauseState() == null ? "NONE" : task.getPauseState());
    }

    /**
     * Pause a task before dispatch — transition to PAUSED state.
     */
    private void pauseTaskBefore(WorkflowTask task) {
        task.setStatus(TaskStatus.PAUSED);
        task.setPauseState("PAUSED_BEFORE");
        task.setPausedAt(Instant.now());
        task.setPauseReason("Waiting for manual review before execution");
        taskRepository.save(task);

        // Transition the parent request to PAUSED
        WorkflowRequest request = task.getRequest();
        if (request.getStatus() == RequestStatus.PENDING || request.getStatus() == RequestStatus.RUNNING) {
            request.setStatus(RequestStatus.PAUSED);
            requestRepository.save(request);
        }

        log.info("Task {} (step '{}') paused BEFORE dispatch — awaiting manual review",
                task.getId(), task.getStepId());
    }

    /**
     * Dispatch a single task onto the unified host-control path. An
     * ORCHESTRATOR step and an ordinary INFERENCE step take the same
     * allocation/session/execution path; they differ only in what
     * {@code session.open}/{@code execution.start} carry (§16.2's assignment
     * versus §8.1's transcript).
     */
    private void dispatchTask(WorkflowTask task) {
        Workflow workflow = task.getRequest().getWorkflow();
        boolean orchestrator = isOrchestratorStep(workflow, task.getStepId());
        if (orchestrator) {
            dispatchWorkflowTask(task, true);
            return;
        }
        dispatchWorkflowTask(task, false);
    }

    /**
     * The single dispatch path for both task families.
     *
     * <ol>
     *   <li><b>Select a host.</b> §3.7: capacity-based and project-preferring,
     *       never profile-keyed. For an orchestration run the §16.4 coordinator
     *       binding is honoured: a pinned coordinator's host is the only
     *       eligible one (no substitute), and its absence throttles with bounded
     *       backoff instead of picking another host.</li>
     *   <li><b>Reserve the slot.</b> {@link SessionAllocator#offer} mints the
     *       session row (OFFERED) under the instance row lock — capacity is
     *       structural, not advisory.</li>
     *   <li><b>Create the attempt</b> (engine-authored, so it exists before the
     *       host answers) and assemble the payloads. For an ORCHESTRATOR step
     *       the §16.2 canonical assignment is assembled and recorded durably in
     *       {@code orchestration_dispatches} inside this transaction, BEFORE any
     *       send — the exact bytes and digest the session will install.</li>
     *   <li><b>Offer + park.</b> {@code session.offer} goes on the wire and the
     *       assembled dispatch is parked in {@link PendingTaskDispatches}; the
     *       host's {@code session.accept}/{@code session.opened} answers resume
     *       it as {@code session.open} then {@code execution.start}.</li>
     * </ol>
     */
    private void dispatchWorkflowTask(WorkflowTask task, boolean orchestrator) {
        Workflow workflow = task.getRequest().getWorkflow();
        UUID requestId = task.getRequest().getId();
        UUID projectId = workflow.getProject().getId();
        UUID profileId = task.getAgentProfile().getId();

        List<AgentHost> candidates = hostSelectionService.selectCandidatesForProject(projectId);

        HostPick pick = orchestrator
                ? selectOrchestrationHost(task, requestId, candidates)
                : selectOrdinaryHost(task, requestId, candidates);
        if (pick == null) {
            return; // throttled/unavailable, or no capacity anywhere
        }
        AgentHost host = pick.host();
        // §16.4: record the pin with the attempt-creating transaction so a
        // concurrent pass under the run lock sees the same coordinator.
        if (pick.coordinatorPin() != null) {
            affinityResolver.recordCoordinator(requestId, null, host.getId());
        }

        UUID sessionId = sessionAllocator.offer(
                SESSION_KIND_TASK, requestId, SERVICE_TYPE_WORKFLOW, projectId, host.getId())
                .orElse(null);
        if (sessionId == null) {
            log.debug("No allocation capacity on host {} for task {}", host.getId(), task.getId());
            return;
        }

        TaskAttempt attempt = taskAttemptService.createAttempt(task, null);
        TaskDispatchContext context;
        try {
            context = orchestrator
                    ? assembleOrchestrationContext(task, requestId, projectId, profileId, attempt)
                    : assembleOrdinaryContext(task, requestId, projectId, profileId, attempt);
        } catch (Exception e) {
            log.error("Dispatch assembly for task {} failed: {}", task.getId(), e.getMessage(), e);
            taskAttemptService.markAbandoned(attempt.getId(),
                    "Dispatch assembly failed: " + e.getMessage());
            sessionAllocator.close(sessionId, "ASSEMBLY_FAILED");
            return;
        }

        boolean offered = hostControlWebSocketHandler.sendSessionOffer(
                sessionId, SESSION_KIND_TASK, requestId);
        if (!offered) {
            // The host socket vanished between selection and send: give the
            // slot back and let the next pass re-select.
            log.info("Session.offer undeliverable for task {} (host socket gone) — releasing slot",
                    task.getId());
            taskAttemptService.markAbandoned(attempt.getId(), "Host socket gone before session.offer");
            sessionAllocator.close(sessionId, "OFFER_UNDELIVERABLE");
            return;
        }

        pendingTaskDispatches.stage(sessionId, context);
        markRunning(task, host);
        log.info("Offered session {} to host {} for task {} (attempt {}, orchestration {})",
                sessionId, host.getId(), task.getId(), attempt.getId(), orchestrator);
    }

    /** A selected host plus the coordinator pin to record (null when unpinned). */
    private record HostPick(AgentHost host, UUID coordinatorPin) {}

    /**
     * §16.4 for an orchestration task: a pinned coordinator's host is the only
     * eligible one — no substitute while pinned. The pinned host is resolved
     * directly rather than from the candidate list, because a pinned host whose
     * instance just died is exactly the case the throttling path exists for (it
     * would otherwise simply vanish from the candidates and the task would be
     * dispatched elsewhere).
     */
    private HostPick selectOrchestrationHost(WorkflowTask task, UUID requestId,
                                             List<AgentHost> candidates) {
        Optional<UUID> coordinator = affinityResolver.coordinatorOf(requestId);
        Optional<UUID> pinnedHostId = orchestrationRunRepository.findById(requestId)
                .map(OrchestrationRun::getCoordinatorHostId)
                .filter(java.util.Objects::nonNull);

        if (pinnedHostId.isPresent()) {
            AgentHost host = agentHostRepository.findById(pinnedHostId.get()).orElse(null);
            if (host != null && hasCapacity(host.getId())) {
                return new HostPick(host, null); // already pinned
            }
            handlePinnedCoordinatorUnavailable(task, requestId, coordinator.orElse(null));
            return null;
        }

        if (candidates.isEmpty()) {
            log.debug("No live hosts available for orchestration run {} (project {})",
                    requestId, task.getRequest().getWorkflow().getProject().getId());
            return null;
        }

        // No host pin yet. §16.4 (2)-(4): while an orchestration attempt is
        // mid-handshake on a host, that host is the effective coordinator — a
        // second task of the same run must not start a handshake elsewhere.
        Optional<AgentHost> inFlight = inFlightOrchestrationHost(requestId, candidates);
        if (inFlight.isPresent()) {
            if (hasCapacity(inFlight.get().getId())) {
                return new HostPick(inFlight.get(), null);
            }
            handlePinnedCoordinatorUnavailable(task, requestId, coordinator.orElse(null));
            return null;
        }

        for (AgentHost candidate : candidates) {
            if (hasCapacity(candidate.getId())) {
                return new HostPick(candidate, candidate.getId());
            }
        }
        log.debug("No host with free capacity for orchestration task {}", task.getId());
        return null;
    }

    /** §3.7 for an ordinary step: the first capacity-bearing candidate wins. */
    private HostPick selectOrdinaryHost(WorkflowTask task, UUID requestId,
                                        List<AgentHost> candidates) {
        for (AgentHost candidate : candidates) {
            if (hasCapacity(candidate.getId())) {
                return new HostPick(candidate, null);
            }
        }
        log.debug("No host with free capacity for task {}", task.getId());
        return null;
    }

    /** §7.1: a host can take a new session when a live instance has a free slot. */
    private boolean hasCapacity(UUID hostId) {
        for (AgentHostInstance instance : hostInstanceRepository
                .findByAgentHostIdAndStatus(hostId, AgentHostInstance.Status.OPEN)) {
            long consuming = sessionAllocator.countConsuming(instance.getId());
            if (consuming < instance.getPoolSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host already running an in-flight orchestration handshake for this
     * run (an OFFERED/INITIALIZING session), if any — the effective coordinator
     * binding while the pin has not been recorded yet.
     */
    private Optional<AgentHost> inFlightOrchestrationHost(UUID requestId,
                                                          List<AgentHost> candidates) {
        for (AgentHost candidate : candidates) {
            for (AgentHostInstance instance : hostInstanceRepository
                    .findByAgentHostIdAndStatus(candidate.getId(), AgentHostInstance.Status.OPEN)) {
                if (sessionAllocator.hasInFlightTasks(instance.getId(), requestId)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** Assemble the §8.1 ordinary-inference dispatch context. */
    private TaskDispatchContext assembleOrdinaryContext(WorkflowTask task, UUID requestId,
                                                        UUID projectId, UUID profileId,
                                                        TaskAttempt attempt) {
        return new TaskDispatchContext(
                task.getId(), attempt.getId(), requestId, projectId, profileId,
                task.getStepId(), false, null, null,
                findStepIndex(task.getRequest().getWorkflow(), task.getStepId()),
                timeoutSecondsOf(task), null);
    }

    /**
     * Assemble the §16.2 orchestration dispatch context: the complete
     * self-contained assignment from the run's pinned Profile version, recorded
     * durably in {@code orchestration_dispatches} inside this transaction, before
     * any send (§16.3).
     */
    private TaskDispatchContext assembleOrchestrationContext(WorkflowTask task, UUID requestId,
                                                             UUID projectId, UUID profileId,
                                                             TaskAttempt attempt) {
        var pinnedVersion = orchestrationRunService.pinnedVersionOf(requestId);
        var continuation = continuationOf(task);
        var assembled = assignmentAssembler.assemble(task, attempt, pinnedVersion, continuation);
        dispatchRepository.save(OrchestrationDispatch.builder()
                .dispatchId(attempt.getId())
                .runId(requestId)
                .taskId(task.getId())
                .assignment(assembled.canonicalJson())
                .assignmentDigest(assembled.assignmentDigest())
                .deliveryState("PENDING")
                .sendCount(0)
                .build());
        return new TaskDispatchContext(
                task.getId(), attempt.getId(), requestId, projectId, profileId,
                task.getStepId(), true, assembled.assignment(),
                assembled.assignmentDigest(),
                findStepIndex(task.getRequest().getWorkflow(), task.getStepId()),
                timeoutSecondsOf(task), continuation);
    }

    /** The step's declared timeout, or the default. */
    private int timeoutSecondsOf(WorkflowTask task) {
        Map<String, Object> step = stepDefOf(task);
        if (step != null && step.get("timeoutSeconds") instanceof Number n) {
            return n.intValue();
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    /** Task + request to RUNNING the first time any task is picked up. */
    private void markRunning(WorkflowTask task, AgentHost host) {
        Instant now = Instant.now();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(now);
        taskRepository.save(task);

        WorkflowRequest request = task.getRequest();
        if (request.getStatus() == RequestStatus.PENDING) {
            request.setStatus(RequestStatus.RUNNING);
            if (request.getStartedAt() == null) {
                request.setStartedAt(now);
            }
            requestRepository.save(request);
        }
    }

    /**
     * Whether the stored step is an ORCHESTRATOR step (§16.1).
     */
    @SuppressWarnings("unchecked")
    private boolean isOrchestratorStep(Workflow workflow, String stepId) {
        if (workflow.getSteps() == null) {
            return false;
        }
        for (Map<String, Object> step : workflow.getSteps()) {
            if (stepId.equals(step.get("id"))) {
                return "ORCHESTRATOR".equals(step.get("taskType"));
            }
        }
        return false;
    }

    /**
     * The §16.2/§17.4 continuation for a resume attempt. Two origins:
     *
     * <ul>
     *   <li>§17.4 HITL approve — the decide path enriched the approval
     *       payload with {@code decisionStatus=APPROVED}; the assembler
     *       embeds the typed decision envelope alongside the directive.</li>
     *   <li>§16.6 FAILED/RETRYABLE reset — the retryable result's stored
     *       structured output carries the §7.3 ContinuationRecord; the
     *       retry dispatch (no decision) restores it agent-side.</li>
     * </ul>
     * Fresh attempts omit it.
     */
    private OrchestrationAssignmentAssembler.ContinuationDirective continuationOf(WorkflowTask task) {
        Map<String, Object> payload = task.getApprovalPayload();
        if (payload != null) {
            Object continuationId = payload.get("suspensionContinuationId");
            Object previousDispatchId = payload.get("previousDispatchId");
            Object decisionStatus = payload.get("decisionStatus");
            if (continuationId != null && previousDispatchId != null
                    && "APPROVED".equals(decisionStatus)) {
                return new OrchestrationAssignmentAssembler.ContinuationDirective(
                        String.valueOf(continuationId), String.valueOf(previousDispatchId));
            }
        }
        // §16.6 retryable reset: the stored structured result's own
        // continuation record (decision-less — an engine retry).
        Map<String, Object> output = task.getOutput();
        if (output != null && output.get("continuation") instanceof Map<?, ?> continuation) {
            Object id = continuation.get("continuationId");
            if (id != null) {
                return new OrchestrationAssignmentAssembler.ContinuationDirective(
                        String.valueOf(id), currentAttemptIdOf(task));
            }
        }
        return null;
    }

    /** The prior attempt id — §16.2 dispatchId (V1) for a retry's
     * previousDispatchId binding. */
    private String currentAttemptIdOf(WorkflowTask task) {
        TaskAttempt prior = task.getCurrentAttempt();
        if (prior != null && prior.getId() != null) {
            return prior.getId().toString();
        }
        return attemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(task.getId())
                .map(a -> a.getId().toString())
                .orElseThrow(() -> new IllegalStateException(
                        "Task " + task.getId() + " has no prior attempt to continue"));
    }

    /**
     * §16.4 (4): while the coordinator is pinned but has no free slot on its
     * host, the task stays PENDING without consuming an attempt. An eligible
     * scheduler pass (past persisted nextEligibleAt) observes the
     * unavailability durably, inserts the deterministic throttled
     * AGENT_UNAVAILABLE scheduling event, and advances nextEligibleAt
     * using the step's bounded exponential backoff — all in one
     * transaction. §16.4 (5)/(8): once the pinned recovery deadline has
     * expired without reconnect proof, the run is terminally LOST.
     */
    private void handlePinnedCoordinatorUnavailable(
            WorkflowTask task, UUID requestId, UUID coordinatorId) {
        Instant now = Instant.now();

        // §16.4 (8): expiry without same-Host lease proof is terminal.
        if (affinityResolver.isRecoveryExpired(requestId, now)) {
            applyWorkspaceLost(task, requestId, coordinatorId);
            return;
        }

        // A scheduler pass before persisted nextEligibleAt emits nothing.
        if (task.getNextEligibleAt() != null && task.getNextEligibleAt().isAfter(now)) {
            return;
        }

        var observation = affinityResolver.observeUnavailable(requestId, task.getId(), now);

        // The throttled §16.4 scheduling event — engine-constructed, never
        // Agent-sourced: EventType.ORCHESTRATION, LogSource.SYSTEM, null
        // attempt/sequence, deterministic UUIDv5 id (idempotent pass).
        persistSchedulingEvent(task, requestId, observation);

        // Bounded exponential backoff from the step's retryPolicy.
        Map<String, Object> stepDef = stepDefOf(task);
        RetryPolicyParser.Backoff backoff = RetryPolicyParser.backoff(stepDef);
        long delaySeconds = backoff.delaySeconds(observation.occurrence());
        task.setNextEligibleAt(now.plusSeconds(delaySeconds));
        taskRepository.save(task);
        log.warn("Run {} coordinator {} unavailable (episode {}, occurrence {}) — "
                + "AGENT_UNAVAILABLE emitted; next eligible pass in {}s "
                + "(recovery deadline {})",
                requestId, coordinatorId, observation.episode(),
                observation.occurrence(), delaySeconds, observation.recoveryDeadline());
    }

    /**
     * §16.4 (8): explicit/expiry loss — atomically mark the run LOST and
     * fail the request with an engine-generated terminal WORKSPACE_LOST
     * result (the Agent is gone; no runner result will arrive).
     */
    private void applyWorkspaceLost(WorkflowTask task, UUID requestId, UUID coordinatorId) {
        OrchestrationRun run = orchestrationRunRepository.findById(requestId)
                .orElse(null);
        if (run != null && !"LOST".equals(run.getLeaseState())) {
            run.setLeaseState("LOST");
            orchestrationRunRepository.save(run);
        }
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.FAILURE);
        // The engine tuple convention (§16.6, matching the HITL rejection):
        // the code lives in errorMessage — WorkflowTask has no errorCode column.
        task.setErrorMessage("WORKSPACE_LOST");
        task.setCompletedAt(Instant.now());
        task.setNextEligibleAt(null);
        taskRepository.save(task);

        WorkflowRequest request = task.getRequest();
        request.setStatus(RequestStatus.FAILED);
        if (request.getCompletedAt() == null) {
            request.setCompletedAt(Instant.now());
        }
        requestRepository.save(request);

        log.error("Run {} coordinator {} recovery deadline expired — run LOST, "
                + "request FAILED with engine-generated WORKSPACE_LOST",
                requestId, coordinatorId);
    }

    /** The step definition map for the task's stepId, or null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> stepDefOf(WorkflowTask task) {
        List<Map<String, Object>> steps = task.getRequest().getWorkflow().getSteps();
        if (steps == null) {
            return null;
        }
        for (Map<String, Object> step : steps) {
            if (task.getStepId().equals(step.get("id"))) {
                return step;
            }
        }
        return null;
    }

    /**
     * The §16.4 throttled scheduling event, persisted directly by the
     * engine (design §16.7 events note): ORCHESTRATION/SYSTEM, null
     * attempt/sequence, deterministic UUIDv5 event id — a repeated pass
     * is idempotent.
     */
    private void persistSchedulingEvent(WorkflowTask task, UUID requestId,
            OrchestrationAffinityResolver.UnavailableObservation observation) {
        if (executionEventRepository.findBySourceEventId(observation.schedulingEventId())
                .isPresent()) {
            return; // idempotent pass
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "AGENT_UNAVAILABLE");
        data.put("runId", requestId.toString());
        data.put("taskId", task.getId().toString());
        data.put("availabilityEpisode", observation.episode());
        data.put("occurrence", observation.occurrence());
        data.put("nextEligibleAt", String.valueOf(task.getNextEligibleAt()));
        data.put("affinityRecoveryDeadline",
                String.valueOf(observation.recoveryDeadline()));
        data.put("occurredAt", Instant.now().toString());

        ExecutionEvent event = new ExecutionEvent();
        event.setId(observation.schedulingEventId()); // deterministic id == row id
        event.setTaskId(task.getId());
        event.setAttemptId(null);
        event.setEventType(EventType.ORCHESTRATION);
        event.setMessage("AGENT_UNAVAILABLE");
        event.setData(data);
        event.setSource(LogSource.SYSTEM);
        event.setSourceEventId(observation.schedulingEventId());
        event.setSequenceNumber(null);
        event.setCreatedAt(Instant.now());
        executionEventRepository.save(event);
    }

    @SuppressWarnings("unchecked")
    private String findStepName(Workflow workflow, String stepId) {
        if (workflow.getSteps() == null) {
            return stepId;
        }
        
        for (Map<String, Object> step : workflow.getSteps()) {
            if (stepId.equals(step.get("id"))) {
                Object name = step.get("name");
                return name != null ? name.toString() : stepId;
            }
        }
        return stepId;
    }
    
    /**
     * Extract the prompt from a workflow step by stepId.
     */
    @SuppressWarnings("unchecked")
    private String findStepPrompt(Workflow workflow, String stepId) {
        if (workflow.getSteps() == null) {
            return null;
        }
        
        for (Map<String, Object> step : workflow.getSteps()) {
            if (stepId.equals(step.get("id"))) {
                Object prompt = step.get("prompt");
                return prompt != null ? prompt.toString() : null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private int findStepIndex(Workflow workflow, String stepId) {
        if (workflow.getSteps() == null) {
            return 0;
        }
        
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            Map<String, Object> step = workflow.getSteps().get(i);
            if (stepId.equals(step.get("id"))) {
                return i;
            }
        }
        return 0;
    }
}
