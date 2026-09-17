package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.workflow.dto.StartWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowRequestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRequestService {

    private final WorkflowRequestRepository requestRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowTaskRepository taskRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final UserRepository userRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final SessionRepository sessionRepository;
    private final SessionExecutionRepository executionRepository;
    private final ExecutionCommandSender executionCommandSender;
    private final TaskAttemptRepository taskAttemptRepository;
    /** Feature 10 (§16.1): run pinning at request creation. */
    private final OrchestrationRunService orchestrationRunService;

    /** §8.8/§21.5 cancel reason/grace — mirrors ConversationTurnDispatcher. */
    private static final String CANCEL_REASON_USER = "USER_REQUESTED";
    private static final int CANCEL_GRACE_SECONDS = 5;
    private static final List<SessionExecution.State> IN_FLIGHT_STATES =
            List.of(SessionExecution.State.STARTING, SessionExecution.State.RUNNING,
                    SessionExecution.State.CANCELLING);

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> findByWorkflow(UUID workflowId) {
        return requestRepository.findByWorkflowId(workflowId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowRequestResponse> findByProject(UUID projectId) {
        return requestRepository.findByWorkflowProjectId(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowRequestResponse findById(UUID id) {
        return requestRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRequest", id.toString()));
    }

    /**
     * Start a new workflow execution.
     * Creates initial tasks for steps with no dependencies.
     */
    @Transactional
    public WorkflowRequestResponse start(StartWorkflowRequest request, UUID userId) {
        Workflow workflow = workflowRepository.findById(request.workflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", request.workflowId().toString()));

        if (workflow.getStatus() != WorkflowStatus.PUBLISHED) {
            throw new IllegalStateException("Can only execute published workflows");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        // Phase 8c: pre-flight token quota at PROJECT scope. Token spend on
        // workflow execution is hard to predict, so we charge a nominal cost
        // up-front and let downstream LLM calls record their actual usage.
        UUID projectId = workflow.getProject() != null ? workflow.getProject().getId() : null;
        if (projectId != null) {
            QuotaDecision decision = quotaPolicyEngine.check(
                    QuotaScope.PROJECT, projectId, QuotaResourceType.TOKENS, 0L);
            if (decision.isBlocked()) {
                throw new ai.myrmec.engine._system.exception.QuotaExceededException(
                        decision.getScopeHit() != null ? decision.getScopeHit() : QuotaScope.PROJECT,
                        projectId,
                        QuotaResourceType.TOKENS,
                        decision.getLimitAmount(),
                        decision.getConsumedAmount());
            }
        }

        WorkflowRequest wfRequest = new WorkflowRequest();
        wfRequest.setWorkflow(workflow);
        wfRequest.setWorkflowVersion(workflow.getVersion());
        wfRequest.setInput(request.input());
        // Status remains PENDING (entity default) until the first task is
        // successfully dispatched to an agent in TaskDispatcherService.
        wfRequest.setCreatedBy(user);

        WorkflowRequest savedRequest = requestRepository.save(wfRequest);

        // Generate feature branch name: myrmec/<short-id>-<sanitized-name>
        String branchName = generateBranchName(savedRequest.getId(), request.input());
        savedRequest.setBranch(branchName);
        savedRequest = requestRepository.save(savedRequest);

        // Feature 10 (§16.1): an orchestrated workflow pins its run at
        // request creation — the bound Profile's currently published
        // version + content digest land on orchestration_runs (id ==
        // request id). Later Profile publishes never affect the run.
        // Pure-inference workflows skip this entirely.
        if (orchestrationRunService != null
                && OrchestrationRunService.hasOrchestratorStep(workflow.getSteps())) {
            UUID boundProfileId = resolveBoundProfileId(workflow);
            orchestrationRunService.pinRun(
                    savedRequest.getId(),
                    workflow.getId(),
                    projectId,
                    boundProfileId);
        }

        // Create tasks for initial steps (no dependencies)
        createInitialTasks(savedRequest, workflow, request.input());

        log.info("Started workflow {} with request {}", workflow.getName(), savedRequest.getId());

        return toResponse(savedRequest);
    }

    /**
     * The workflow's orchestration binding target: the single workflow-local
     * agentProfileCode alias bound at publication (§16.1). Falls back to the
     * first step's agentProfileId when bindings are absent (legacy seeds).
     */
    private UUID resolveBoundProfileId(Workflow workflow) {
        Map<String, Object> bindings = workflow.getOrchestrationBindings();
        if (bindings != null && !bindings.isEmpty()) {
            Object any = bindings.values().iterator().next();
            try {
                return UUID.fromString(String.valueOf(any));
            } catch (IllegalArgumentException ignored) {
                // fall through to the step profile
            }
        }
        List<Map<String, Object>> steps = workflow.getSteps();
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                Object profileId = step.get("agentProfileId");
                if (profileId != null && !profileId.toString().isBlank()) {
                    return UUID.fromString(profileId.toString());
                }
            }
        }
        throw new IllegalStateException(
                "Orchestrated workflow " + workflow.getId() + " has no bound agent profile.");
    }

    /**
     * Create tasks for steps with no dependencies.
     */
    @SuppressWarnings("unchecked")
    private void createInitialTasks(WorkflowRequest request, Workflow workflow, Map<String, Object> input) {
        List<Map<String, Object>> steps = workflow.getSteps();
        if (steps == null || steps.isEmpty()) {
            log.warn("Workflow {} has no steps", workflow.getId());
            return;
        }

        for (Map<String, Object> step : steps) {
            List<String> dependsOn = (List<String>) step.get("dependsOn");
            
            // Only create tasks for steps with no dependencies
            if (dependsOn == null || dependsOn.isEmpty()) {
                createTask(request, step, input);
            }
        }
    }

    /**
     * Create a task for a workflow step.
     */
    @SuppressWarnings("unchecked")
    private void createTask(WorkflowRequest request, Map<String, Object> step, Map<String, Object> input) {
        String stepId = (String) step.get("id");
        String agentProfileIdStr = (String) step.get("agentProfileId");
        
        if (agentProfileIdStr == null) {
            log.error("Step {} has no agentProfileId", stepId);
            return;
        }

        UUID agentProfileId = UUID.fromString(agentProfileIdStr);
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("AgentProfile", agentProfileIdStr));

        // Build task input from workflow input + step prompt
        Map<String, Object> taskInput = new HashMap<>(input);
        if (step.containsKey("prompt")) {
            taskInput.put("prompt", step.get("prompt"));
        }

        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId(stepId);
        task.setAgentProfile(profile);
        task.setInput(taskInput);
        task.setKnowledgeSourceIds(parseKnowledgeSourceIds(step.get("knowledgeSourceIds")));
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);
        // Copy pause mode and max retries from step definition
        task.setPauseMode(parsePauseMode(step.get("pauseMode")));
        task.setMaxRetries(RetryPolicyParser.maxRetries(step));

        taskRepository.save(task);
        log.info("Created task for step {} in request {}", stepId, request.getId());
    }

    @Transactional
    public WorkflowRequestResponse cancel(UUID id) {
        WorkflowRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRequest", id.toString()));

        if (request.getStatus() == RequestStatus.COMPLETED ||
            request.getStatus() == RequestStatus.FAILED ||
            request.getStatus() == RequestStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel a finished request");
        }

        request.setStatus(RequestStatus.CANCELLED);
        WorkflowRequest saved = requestRepository.save(request);

        // Cascade: cancel all non-terminal tasks.
        // - PENDING/READY: never dispatched, just mark CANCELLED.
        // - RUNNING: mark CANCELLED, abandon the active attempt, and relay
        //   execution.cancel over the unified host socket (§8.8) so the host
        //   can unwind the attempt. (P6-T6: the legacy task.cancel /
        //   inference.cancel split is gone — both families cancel through
        //   the same execution seam.)
        // - PAUSED: mark CANCELLED (the pause gate is moot once the request
        //   is cancelled; WorkflowTaskPauseService.continueTask will reject
        //   any subsequent continue because the task is no longer PAUSED).
        Instant now = Instant.now();
        for (WorkflowTask task : taskRepository.findByRequestId(saved.getId())) {
            if (task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.READY
                    || task.getStatus() == TaskStatus.RUNNING
                    || task.getStatus() == TaskStatus.PAUSED) {

                // For RUNNING tasks, abandon the active attempt and notify the host.
                if (task.getStatus() == TaskStatus.RUNNING) {
                    taskAttemptRepository
                            .findFirstByTaskIdOrderByAttemptNumberDesc(task.getId())
                            .ifPresent(attempt -> {
                                attempt.markAbandoned("Request cancelled by user");
                                taskAttemptRepository.save(attempt);
                            });

                    // Unified protocol: relay execution.cancel on the task's
                    // in-flight WORKFLOW execution, if one exists. Sessions are
                    // keyed by the dispatch requestId (TaskDispatcherService).
                    relayExecutionCancel(task, "Request cancelled by user");
                }

                task.setStatus(TaskStatus.CANCELLED);
                task.setCompletedAt(now);
                taskRepository.save(task);
            }
        }

        return toResponse(saved);
    }

    /**
     * Unified protocol (§8.8): relay {@code execution.cancel} for a RUNNING
     * task's in-flight WORKFLOW execution. The task's session is keyed by the
     * dispatch {@code requestId} (TaskDispatcherService allocates
     * refId=requestId); an absent session (never dispatched) or absent
     * in-flight execution (already terminal) is a clean no-op. The attempt row
     * was already abandoned by the caller.
     */
    private void relayExecutionCancel(WorkflowTask task, String reason) {
        try {
            Session session = sessionRepository
                    .findByRefIdAndServiceType(task.getRequest().getId(), "WORKFLOW")
                    .orElse(null);
            if (session == null) {
                log.debug("No WORKFLOW session for request {} — task {} cancel is state-only",
                        task.getRequest().getId(), task.getId());
                return;
            }
            SessionExecution inFlight = executionRepository
                    .findWithLockBySessionIdAndStateIn(session.getId(), IN_FLIGHT_STATES)
                    .stream().findFirst().orElse(null);
            if (inFlight == null) {
                log.debug("No in-flight execution on session {} — task {} cancel is state-only",
                        session.getId(), task.getId());
                return;
            }
            boolean delivered = executionCommandSender.cancel(
                    inFlight.getId(), session, null, CANCEL_REASON_USER, CANCEL_GRACE_SECONDS);
            if (delivered) {
                log.info("Relayed execution.cancel for task {} (execution {})",
                        task.getId(), inFlight.getId());
            } else {
                log.debug("Host socket gone for task {} — execution.cancel not delivered; "
                        + "host-lost sweep reconciles", task.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to relay execution.cancel for task {}: {}",
                    task.getId(), e.getMessage());
        }
    }

    /**
     * Generate a feature branch name from the request ID and input.
     * Format: myrmec/{short-id}-{sanitized-feature-name}
     */
    private String generateBranchName(UUID requestId, Map<String, Object> input) {
        String shortId = requestId.toString().substring(0, 8);

        // Try to get a human-readable name from input
        String featureName = null;
        if (input != null) {
            Object name = input.get("featureName");
            if (name == null) name = input.get("name");
            if (name == null) name = input.get("feature");
            if (name != null) featureName = name.toString();
        }

        if (featureName != null && !featureName.isBlank()) {
            // Sanitize: lowercase, replace non-alphanumeric with hyphens, collapse, trim
            String sanitized = featureName.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            // Truncate to keep branch name reasonable
            if (sanitized.length() > 50) {
                sanitized = sanitized.substring(0, 50).replaceAll("-$", "");
            }
            return "myrmec/" + shortId + "-" + sanitized;
        }

        return "myrmec/" + shortId;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseKnowledgeSourceIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) {
                    ids.add(value.toString());
                }
            }
            return ids;
        }
        if (raw.toString().isBlank()) {
            return List.of();
        }
        return List.of(raw.toString());
    }

    private PauseMode parsePauseMode(Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return PauseMode.NONE;
        }
        try {
            return PauseMode.valueOf(raw.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown pauseMode '{}', defaulting to NONE", raw);
            return PauseMode.NONE;
        }
    }

    private WorkflowRequestResponse toResponse(WorkflowRequest request) {
        return new WorkflowRequestResponse(
                request.getId(),
                request.getWorkflow().getId(),
                request.getWorkflow().getName(),
                request.getWorkflowVersion(),
                request.getInput(),
                request.getOutput(),
                request.getStatus(),
                request.getErrorMessage(),
                request.getCreatedBy().getId(),
                request.getCreatedBy().getEmail(),
                request.getCreatedAt(),
                request.getStartedAt(),
                request.getCompletedAt()
        );
    }
}
