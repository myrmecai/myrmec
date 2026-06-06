package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.*;
import ai.myrmec.engine.knowledge.TaskContextResolver;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.tool.ToolService;
import ai.myrmec.engine.tool.dto.ToolResponse;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.TaskAssignPayload;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
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
    private final AgentRepository agentRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;
    private final ToolService toolService;
    private final ModelService modelService;
    private final TaskContextResolver contextResolver;
    private final TaskAttemptService taskAttemptService;

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
                // Skip tasks belonging to a cancelled request. The task will
                // be marked CANCELLED by the cancellation flow; this is a
                // safety net in case ordering of saves was off.
                RequestStatus reqStatus = task.getRequest().getStatus();
                if (reqStatus == RequestStatus.CANCELLED
                        || reqStatus == RequestStatus.COMPLETED
                        || reqStatus == RequestStatus.FAILED) {
                    continue;
                }
                dispatchTask(task);
            } catch (Exception e) {
                log.error("Failed to dispatch task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    /**
     * Dispatch a single task to an available agent.
     */
    private void dispatchTask(WorkflowTask task) {
        UUID profileId = task.getAgentProfile().getId();
        
        // Find agents with matching profile
        List<Agent> matchingAgents = agentRepository.findActiveByProfileId(profileId);
        
        if (matchingAgents.isEmpty()) {
            log.debug("No active agents found for profile {}", profileId);
            return;
        }
        
        // Find an available agent instance (online, idle)
        for (Agent agent : matchingAgents) {
            Optional<AgentInstance> availableInstance = findAvailableInstance(agent.getId());
            
            if (availableInstance.isPresent()) {
                AgentInstance instance = availableInstance.get();
                
                // Create attempt record
                TaskAttempt attempt = taskAttemptService.createAttempt(task, instance);
                
                // Build task payload with attempt info
                TaskAssignPayload payload = buildTaskPayload(task, attempt);
                
                // Try to send task to agent
                boolean sent = webSocketHandler.assignTask(instance.getId(), payload);
                
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
    private Optional<AgentInstance> findAvailableInstance(UUID agentId) {
        List<AgentInstance> instances = agentInstanceRepository.findByAgentIdAndStatus(
                agentId, AgentInstance.Status.ONLINE);
        
        for (AgentInstance instance : instances) {
            // Check if instance is idle (not working on a task)
            if (connectionManager.isAgentIdle(instance.getId())) {
                return Optional.of(instance);
            }
        }
        
        return Optional.empty();
    }

    /**
     * Build the task assignment payload.
     */
    private TaskAssignPayload buildTaskPayload(WorkflowTask task, TaskAttempt attempt) {
        WorkflowRequest request = task.getRequest();
        Workflow workflow = request.getWorkflow();
        
        // Get tools
        List<TaskAssignPayload.ToolDefinition> tools = toolService.findActive().stream()
                .map(this::toToolDefinition)
                .toList();
        
        // Get model info with credentials
        AgentProfile profile = task.getAgentProfile();
        TaskAssignPayload.ModelInfo modelInfo = null;
        log.debug("Building task payload for profile '{}', defaultModel='{}'", 
                profile.getName(), profile.getDefaultModel());
        
        if (profile.getDefaultModel() != null) {
            try {
                Model model = modelService.findByCode(profile.getDefaultModel());
                log.debug("Found model '{}': provider={}, requiresAuth={}, hasApiKey={}", 
                        model.getCode(), model.getProvider(), model.isRequiresAuth(), 
                        model.getApiKeyEncrypted() != null);
                
                // Get decrypted API key
                String apiKey = null;
                if (model.isRequiresAuth() && model.getApiKeyEncrypted() != null) {
                    apiKey = modelService.getApiKey(model.getCode());
                    log.debug("Decrypted API key for model '{}': length={}", 
                            model.getCode(), apiKey != null ? apiKey.length() : 0);
                } else {
                    log.warn("Model '{}' requires auth but has no API key configured", model.getCode());
                }
                
                // Get API endpoint (use provider default if not set on model)
                String apiEndpoint = model.getApiEndpoint();
                if (apiEndpoint == null && model.getProviderConfig() != null) {
                    apiEndpoint = model.getProviderConfig().getBaseUrl();
                }
                
                modelInfo = TaskAssignPayload.ModelInfo.builder()
                        .provider(model.getProvider())
                        .modelId(model.getModelId())
                        .apiEndpoint(apiEndpoint)
                        .apiKey(apiKey)
                        .parameters(model.getDefaultParams())
                        .build();
                log.debug("Built modelInfo: provider={}, modelId={}, endpoint={}, hasApiKey={}", 
                        modelInfo.getProvider(), modelInfo.getModelId(), 
                        modelInfo.getApiEndpoint(), modelInfo.getApiKey() != null);
            } catch (Exception e) {
                log.warn("Could not load model {}: {}", profile.getDefaultModel(), e.getMessage());
            }
        } else {
            log.warn("Agent profile '{}' has no default model configured", profile.getName());
        }
        
        // Get task context (knowledge docs, instructions, workspace)
        TaskContext context = contextResolver.resolve(
                workflow.getProject().getId(),
                task.getStepId(),
                workflow.getArtifactsRepo()  // Pass workflow repo config
        );
        
        // Override workspace branch with execution-specific feature branch
        if (request.getBranch() != null && context.getWorkspace() != null) {
            context.getWorkspace().setBranch(request.getBranch());
        }
        
        // Find step info from workflow steps
        String stepName = findStepName(workflow, task.getStepId());
        int stepIndex = findStepIndex(workflow, task.getStepId());
        String stepPrompt = findStepPrompt(workflow, task.getStepId());
        
        // Get system prompt from agent profile
        String systemPrompt = profile.getSystemPrompt();
        
        return TaskAssignPayload.builder()
                .taskId(task.getId())
                .attemptId(attempt.getId())
                .attemptNumber(attempt.getAttemptNumber())
                .workflowId(workflow.getId())
                .stepIndex(stepIndex)
                .stepName(stepName)
                .systemPrompt(systemPrompt)
                .stepPrompt(stepPrompt)
                .input(task.getInput())
                .tools(tools)
                .timeoutSeconds(300) // 5 minute default
                .model(modelInfo)
                .context(context)
                .build();
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
