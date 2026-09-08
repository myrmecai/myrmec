// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.*;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.tool.ToolService;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.TaskAssignPayload;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.inference.InferenceRequestAssembler;
import ai.myrmec.engine.inference.InferenceRequestSpec;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.knowledge.TaskContextResolver;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatcherService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    private final AgentHostRepository agentRepository;
    private final AgentRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;
    private final ToolService toolService;
    private final ModelService modelService;
    private final TaskAttemptService taskAttemptService;
    private final TaskAttemptRepository attemptRepository;
    private final TaskContextResolver contextResolver;
    private final SessionContextAssembler sessionContextAssembler;
    private final ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;
    private final InferenceRequestAssembler inferenceRequestAssembler;
    private final ai.myrmec.engine.governance.GovernancePolicyResolver governancePolicyResolver;
    // Feature 10 (§16.2/§16.3/§16.4): the orchestration dispatch pipeline.
    private final OrchestrationAffinityResolver affinityResolver;
    private final OrchestrationRunService orchestrationRunService;
    private final OrchestrationAssignmentAssembler assignmentAssembler;
    private final OrchestrationDispatchRelay dispatchRelay;
    // §16.4(4-8): availability throttling + terminal loss persistence.
    private final OrchestrationRunRepository orchestrationRunRepository;
    private final ExecutionEventRepository executionEventRepository;

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
     * Dispatch a single task to an available agent.
     *
     * Feature 10: an ORCHESTRATOR step dispatches through the durable
     * orchestration pipeline (§16.2/§16.3) — assemble the complete
     * self-contained assignment, record it in {@code orchestration_dispatches}
     * inside the attempt-creating transaction, then send the exact stored
     * bytes through the resend-until-accept relay. Ordinary inference steps
     * keep the existing session.open + inference.assign path unchanged.
     */
    private void dispatchTask(WorkflowTask task) {
        Workflow workflow = task.getRequest().getWorkflow();
        boolean orchestrator = isOrchestratorStep(workflow, task.getStepId());
        if (orchestrator) {
            dispatchOrchestrationTask(task);
            return;
        }

        UUID profileId = task.getAgentProfile().getId();
        
        // Find agents with matching profile
        List<AgentHost> matchingAgents = agentRepository.findActiveByProfileId(profileId);
        
        if (matchingAgents.isEmpty()) {
            log.debug("No active agents found for profile {}", profileId);
            return;
        }
        
        // Find an available agent instance (online, idle)
        for (AgentHost agent : matchingAgents) {
            Optional<Agent> availableInstance = findAvailableInstance(agent.getId());
            
            if (availableInstance.isPresent()) {
                Agent instance = availableInstance.get();
                
                // Create attempt record
                TaskAttempt attempt = taskAttemptService.createAttempt(task, instance);
                
                // Build session.open + inference.assign via the unified
                // inference dispatch pipeline (§6.1 + §6.2).
                SessionOpenPayload sessionOpen = buildSessionOpen(task, attempt);
                InferenceAssignPayload payload = buildInferenceAssign(task, attempt, sessionOpen);
                
                // Send session.open first, then inference.assign
                boolean sent = webSocketHandler.sendSessionOpen(instance.getId(), sessionOpen)
                        && webSocketHandler.sendInferenceAssign(instance.getId(), payload);
                
                if (sent) {
                    // Update task status
                    Instant now = Instant.now();
                    task.setStatus(TaskStatus.RUNNING);
                    task.setAgentInstance(instance);
                    task.setStartedAt(now);
                    taskRepository.save(task);

                    // Transition the parent request to RUNNING the first time
                    // any of its tasks is actually picked up by an agent.
                    WorkflowRequest request = task.getRequest();
                    if (request.getStatus() == RequestStatus.PENDING) {
                        request.setStatus(RequestStatus.RUNNING);
                        if (request.getStartedAt() == null) {
                            request.setStartedAt(now);
                        }
                        requestRepository.save(request);
                    }

                    log.info("Dispatched task {} (attempt {}) to agent instance {}",
                            task.getId(), attempt.getAttemptNumber(), instance.getId());
                    return;
                } else {
                    // Failed to send - mark attempt as abandoned
                    taskAttemptService.markAbandoned(attempt.getId(), "Failed to send to agent");
                }
            }
        }
        
        log.debug("No available agent instances for task {}", task.getId());
    }

    /**
     * Find an available agent instance (online and idle).
     */
    private Optional<Agent> findAvailableInstance(UUID agentId) {
        List<Agent> instances = agentInstanceRepository.findByAgentHostIdAndStatus(
                agentId, Agent.Status.IDLE);
        
        for (Agent instance : instances) {
            // Check if instance is idle (not working on a task)
            if (connectionManager.isAgentIdle(instance.getId())) {
                return Optional.of(instance);
            }
        }
        
        return Optional.empty();
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
     * Dispatch one orchestration task (§16.3 Engine→Agent delivery):
     *
     * <ol>
     *   <li>Affinity (§16.4): prefer the run's pinned coordinator instance;
     *       the first dispatch selects it.</li>
     *   <li>Create the attempt (dispatchId = attempt UUID in V1).</li>
     *   <li>Assemble the complete self-contained assignment from the run's
     *       pinned Profile version (§16.2).</li>
     *   <li>Record the dispatch durably — canonical bytes + digest +
     *       PENDING — inside this transaction, BEFORE any send.</li>
     *   <li>Send the exact stored bytes through the resend-until-accept
     *       relay; a send failure leaves the row PENDING for the relay.</li>
     * </ol>
     */
    private void dispatchOrchestrationTask(WorkflowTask task) {
        UUID requestId = task.getRequest().getId();

        // Affinity (§16.4): the coordinator instance if already selected.
        java.util.Optional<UUID> coordinator = affinityResolver.coordinatorOf(requestId);

        UUID profileId = task.getAgentProfile().getId();
        List<AgentHost> matchingAgents = agentRepository.findActiveByProfileId(profileId);
        if (matchingAgents.isEmpty()) {
            log.debug("No active agents found for profile {}", profileId);
            return;
        }

        // §16.4 (2)-(4): a PINNED coordinator is the only eligible instance —
        // no substitute while pinned. When it is not connected/idle, the
        // task stays PENDING (throttled events + backoff; terminal loss
        // after the pinned recovery deadline) — handled exactly once.
        if (coordinator.isPresent()) {
            for (AgentHost agent : matchingAgents) {
                java.util.Optional<Agent> pinned = findByIdIfAlive(agent, coordinator.get());
                if (pinned.isPresent()) {
                    dispatchToInstance(task, requestId, pinned.get());
                    return;
                }
            }
            handlePinnedCoordinatorUnavailable(task, requestId, coordinator.get());
            return;
        }

        // No coordinator yet: the first available eligible instance wins
        // and is pinned by the dispatch (§16.4 (1)-(2)).
        for (AgentHost agent : matchingAgents) {
            java.util.Optional<Agent> instance = findAvailableInstance(agent.getId());
            if (instance.isPresent()) {
                dispatchToInstance(task, requestId, instance.get());
                return;
            }
        }

        log.debug("No available agent instances for orchestration task {}", task.getId());
    }

    /**
     * The attempt-creating dispatch: record the attempt + dispatch row,
     * pin the coordinator (first dispatch wins), assemble from the run's
     * pinned Profile version, send the exact stored bytes.
     */
    private void dispatchToInstance(WorkflowTask task, UUID requestId, Agent selected) {
        // The attempt-creating transaction commits the dispatch row
        // before any send (§16.3 durable delivery).
        TaskAttempt attempt = taskAttemptService.createAttempt(task, selected);

        // §16.4 (2): the first dispatch pins the coordinator.
        affinityResolver.recordCoordinator(
                requestId, selected.getId(), selected.getAgentHostId());

        try {
            var pinnedVersion = orchestrationRunService.pinnedVersionOf(requestId);
            // §16.2/§17.4: a resume attempt carries the typed
            // continuation from the stored approval payload — the
            // suspended continuation + prior dispatch. Fresh
            // attempts omit it.
            var continuation = continuationOf(task);
            var assembled = assignmentAssembler.assemble(task, attempt, pinnedVersion, continuation);
            // §16.2: dispatchId == the attempt UUID in V1.
            var dispatch = dispatchRelay.recordDispatch(
                    attempt.getId(),
                    requestId,
                    task.getId(),
                    assembled.canonicalJson(),
                    assembled.assignmentDigest());

            boolean sent = dispatchRelay.sendOnce(dispatch, selected.getId());
            if (!sent) {
                // Row stays PENDING — the relay retransmits on the
                // next dispatch pass (or after reconnect).
                log.info("Orchestration dispatch {} for task {} not sent yet; relay pending",
                        attempt.getId(), task.getId());
            }

            Instant now = Instant.now();
            task.setStatus(TaskStatus.RUNNING);
            task.setAgentInstance(selected);
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

            log.info("Dispatched orchestration task {} (attempt {}, dispatch {}) to agent {}",
                    task.getId(), attempt.getAttemptNumber(), attempt.getId(), selected.getId());
        } catch (Exception e) {
            log.error("Orchestration dispatch for task {} failed: {}",
                    task.getId(), e.getMessage(), e);
            taskAttemptService.markAbandoned(attempt.getId(),
                    "Orchestration assembly/dispatch failed: " + e.getMessage());
        }
    }

    /** The coordinator instance when it belongs to this host and is idle. */
    private java.util.Optional<Agent> findByIdIfAlive(AgentHost agent, UUID instanceId) {
        try {
            List<Agent> instances = agentInstanceRepository
                    .findByAgentHostIdAndStatus(agent.getId(), Agent.Status.IDLE);
            for (Agent instance : instances) {
                if (instance.getId().equals(instanceId)
                        && connectionManager.isAgentIdle(instance.getId())) {
                    return java.util.Optional.of(instance);
                }
            }
        } catch (Exception e) {
            log.debug("Coordinator lookup failed: {}", e.getMessage());
        }
        return java.util.Optional.empty();
    }

    /**
     * §16.4 (4): while the coordinator is pinned but not connected/idle,
     * the task stays PENDING without consuming an attempt. An eligible
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

    /**
     * Build the task assignment payload.
     */
    /**
     * Build the session.open payload for a workflow task (§6.1).
     * Delegates to {@link SessionContextAssembler} which creates the
     * Session row and resolves model/workspace/tools/KB handles.
     */
    private SessionOpenPayload buildSessionOpen(WorkflowTask task, TaskAttempt attempt) {
        WorkflowRequest request = task.getRequest();
        Workflow workflow = request.getWorkflow();
        AgentProfile profile = task.getAgentProfile();
        return sessionContextAssembler.assemble(
                "WORKFLOW",
                request.getId(),          // refId = workflow_request_id
                workflow.getProject().getId(),
                profile.getId());
    }

    /**
     * Build the inference.assign payload for a workflow task (§6.2).
     * Assembles the transcript via {@link InferenceRequestAssembler}
     * using the workflow composer.
     */
    private InferenceAssignPayload buildInferenceAssign(WorkflowTask task, TaskAttempt attempt,
                                                        SessionOpenPayload sessionOpen) {
        WorkflowRequest request = task.getRequest();
        Workflow workflow = request.getWorkflow();
        AgentProfile profile = task.getAgentProfile();
        // §16.1: the behaviour contract (system prompt) lives on the
        // published version row, not on the profile.
        ai.myrmec.engine.agent.AgentProfileVersion publishedVersion = agentProfileVersionService
                .findPublished(profile.getId()).orElse(null);

        // Resolve task context (instruction assets + knowledge)
        TaskContext context = contextResolver.resolve(
                workflow.getProject().getId(),
                task.getStepId(),
                null);
        // Override workspace branch with execution-specific feature branch
        if (request.getBranch() != null && context.getWorkspace() != null) {
            context.getWorkspace().setBranch(request.getBranch());
        }

        // Map TaskContext.KnowledgeEntry → InferenceRequestSpec.KnowledgeEntry
        List<InferenceRequestSpec.KnowledgeEntry> knowledge = List.of();
        if (context.getKnowledge() != null) {
            knowledge = context.getKnowledge().stream()
                    .map(k -> new InferenceRequestSpec.KnowledgeEntry(
                            k.getName(), k.getContent(), k.getCategory()))
                    .toList();
        }

        String stepPrompt = findStepPrompt(workflow, task.getStepId());
        int stepIndex = findStepIndex(workflow, task.getStepId());

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .sessionId(sessionOpen.sessionId())
                .requestId(task.getId())              // requestId = task id
                .projectId(workflow.getProject().getId())
                .sequenceNo(stepIndex)
                .stepId(task.getStepId())              // step id for routing (nullable for conversation)
                .governanceProfileCode(governancePolicyResolver.resolveOrgDefault().code())
                .systemPrompt(publishedVersion != null ? publishedVersion.getSystemPrompt() : null)
                .stepPrompt(stepPrompt)
                .input(task.getInput())
                .knowledge(knowledge)
                .build();

        return inferenceRequestAssembler.assemble(spec);
    }

    private TaskAssignPayload.ToolDefinition toToolDefinition(ToolResponse tool) {
        return TaskAssignPayload.ToolDefinition.builder()
                .name(tool.code())  // Use code for agent registry matching
                .description(tool.description())
                .parameters(tool.configSchema())
                .build();
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
