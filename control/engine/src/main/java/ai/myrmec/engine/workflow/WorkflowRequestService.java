package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
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

        // Create tasks for initial steps (no dependencies)
        createInitialTasks(savedRequest, workflow, request.input());

        log.info("Started workflow {} with request {}", workflow.getName(), savedRequest.getId());

        return toResponse(savedRequest);
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
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(1);

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

        // Cascade: cancel any tasks that have not yet been picked up by an
        // agent so the dispatcher doesn't send them out after cancellation.
        // Tasks already RUNNING are left as-is; the agent will report back.
        Instant now = Instant.now();
        for (WorkflowTask task : taskRepository.findByRequestId(saved.getId())) {
            if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.READY) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setCompletedAt(now);
                taskRepository.save(task);
            }
        }

        return toResponse(saved);
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
