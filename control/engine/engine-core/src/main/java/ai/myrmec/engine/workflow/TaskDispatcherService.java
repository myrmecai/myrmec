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
    private final TaskContextResolver contextResolver;
    private final SessionContextAssembler sessionContextAssembler;
    private final ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;
    private final InferenceRequestAssembler inferenceRequestAssembler;
    private final ai.myrmec.engine.governance.GovernancePolicyResolver governancePolicyResolver;

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
     */
    private void dispatchTask(WorkflowTask task) {
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
