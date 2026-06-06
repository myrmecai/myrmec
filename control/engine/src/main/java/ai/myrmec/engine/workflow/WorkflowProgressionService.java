package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Handles workflow step progression after a task completes.
 *
 * When a task finishes, this service:
 * 1. Evaluates transitions (conditional routing based on task output)
 * 2. Finds dependent steps whose dependencies are now satisfied
 * 3. Creates new tasks for those steps
 * 4. Completes the workflow request when all terminal steps are done
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowProgressionService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    private final AgentProfileRepository agentProfileRepository;

    /**
     * Called after a task completes successfully.
     * Evaluates transitions and creates tasks for next steps.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void onTaskCompleted(WorkflowTask completedTask) {
        WorkflowRequest request = completedTask.getRequest();
        Workflow workflow = request.getWorkflow();
        String completedStepId = completedTask.getStepId();

        log.info("Processing progression for completed step '{}' in request {}",
                completedStepId, request.getId());

        List<Map<String, Object>> steps = workflow.getSteps();
        if (steps == null || steps.isEmpty()) {
            return;
        }

        // Find the completed step definition
        Map<String, Object> completedStepDef = findStepDef(steps, completedStepId);
        if (completedStepDef == null) {
            log.warn("Step definition not found for '{}' in workflow {}", completedStepId, workflow.getId());
            return;
        }

        // Determine the next step ID from transitions or dependsOn
        String nextStepId = resolveNextStep(completedStepDef, completedTask, steps);

        if (nextStepId != null) {
            // Create task for the resolved next step
            Map<String, Object> nextStepDef = findStepDef(steps, nextStepId);
            if (nextStepDef != null) {
                createNextTask(request, nextStepDef, completedTask);
            } else {
                log.warn("Next step '{}' not found in workflow definition", nextStepId);
            }
        } else {
            // No explicit next step from transitions — check if this unblocks any dependent steps
            createUnblockedTasks(request, workflow, completedStepId);
        }

        // Check if workflow is complete (all steps done or no more steps to run)
        checkWorkflowCompletion(request, workflow);
    }

    /**
     * Called after a task fails (after retries exhausted).
     */
    @Transactional
    public void onTaskFailed(WorkflowTask failedTask) {
        WorkflowRequest request = failedTask.getRequest();

        log.warn("Step '{}' failed in request {}, marking workflow as failed",
                failedTask.getStepId(), request.getId());

        request.setStatus(RequestStatus.FAILED);
        request.setErrorMessage("Step '" + failedTask.getStepId() + "' failed: " +
                failedTask.getErrorMessage());
        request.setCompletedAt(java.time.Instant.now());
        requestRepository.save(request);
    }

    /**
     * Resolve the next step using transitions (conditional routing).
     *
     * Transitions map in step definition:
     *   "transitions": { "approved": "document", "rejected": "generate" }
     *
     * The task output must contain an "outcome" field that matches a transition key.
     * If no transitions defined, returns null (fall through to dependsOn logic).
     */
    @SuppressWarnings("unchecked")
    private String resolveNextStep(
            Map<String, Object> stepDef,
            WorkflowTask completedTask,
            List<Map<String, Object>> allSteps
    ) {
        Map<String, String> transitions = (Map<String, String>) stepDef.get("transitions");
        if (transitions == null || transitions.isEmpty()) {
            return null;
        }

        // Get outcome from task output
        Map<String, Object> output = completedTask.getOutput();
        String outcome = null;
        if (output != null) {
            Object outcomeObj = output.get("outcome");
            if (outcomeObj != null) {
                outcome = outcomeObj.toString();
            }
        }

        if (outcome == null) {
            // No outcome in output — try to infer from response content
            outcome = inferOutcome(output, transitions.keySet());
        }

        if (outcome != null && transitions.containsKey(outcome)) {
            String nextStepId = transitions.get(outcome);
            log.info("Transition: step outcome '{}' → next step '{}'", outcome, nextStepId);
            return nextStepId;
        }

        // Default transition if present
        if (transitions.containsKey("default")) {
            String defaultNext = transitions.get("default");
            log.info("Transition: no matching outcome '{}', using default → '{}'", outcome, defaultNext);
            return defaultNext;
        }

        log.warn("No matching transition for outcome '{}' in step, available transitions: {}",
                outcome, transitions.keySet());
        return null;
    }

    /**
     * Infer an outcome from the task output by scanning the response text.
     * Looks for structured markers first (OUTCOME: key), then keywords matching transition keys.
     */
    private String inferOutcome(Map<String, Object> output, Set<String> transitionKeys) {
        if (output == null) {
            return null;
        }

        // Check the "response" field (standard LLM output field)
        Object responseObj = output.get("response");
        if (responseObj == null) {
            return null;
        }

        String response = responseObj.toString();

        // First: look for explicit OUTCOME marker (e.g., "OUTCOME: approved")
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "OUTCOME:\\s*(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            String outcomeValue = matcher.group(1).toLowerCase();
            if (transitionKeys.contains(outcomeValue)) {
                log.debug("Found explicit OUTCOME marker: '{}'", outcomeValue);
                return outcomeValue;
            }
        }

        // Second: keyword match in response text
        String responseLower = response.toLowerCase();
        for (String key : transitionKeys) {
            if ("default".equals(key)) continue;
            if (responseLower.contains(key.toLowerCase())) {
                log.debug("Inferred outcome '{}' from response content", key);
                return key;
            }
        }

        return null;
    }

    /**
     * Create tasks for steps whose dependencies are now all satisfied.
     * Used when a step has no explicit transitions.
     */
    @SuppressWarnings("unchecked")
    private void createUnblockedTasks(WorkflowRequest request, Workflow workflow, String completedStepId) {
        List<Map<String, Object>> steps = workflow.getSteps();

        for (Map<String, Object> stepDef : steps) {
            String stepId = (String) stepDef.get("id");
            List<String> dependsOn = (List<String>) stepDef.get("dependsOn");

            if (dependsOn == null || dependsOn.isEmpty()) {
                continue; // Initial step — already created at workflow start
            }

            if (!dependsOn.contains(completedStepId)) {
                continue; // This step doesn't depend on the completed one
            }

            // Check if ALL dependencies are satisfied
            boolean allDependenciesMet = true;
            Map<String, Object> mergedOutput = new HashMap<>();

            for (String depStepId : dependsOn) {
                List<WorkflowTask> depTasks = taskRepository.findByRequestIdAndStatus(
                        request.getId(), TaskStatus.COMPLETED);

                Optional<WorkflowTask> depTask = depTasks.stream()
                        .filter(t -> depStepId.equals(t.getStepId()) && t.getResult() == TaskResult.SUCCESS)
                        .findFirst();

                if (depTask.isEmpty()) {
                    allDependenciesMet = false;
                    break;
                }

                // Merge output from dependency
                if (depTask.get().getOutput() != null) {
                    mergedOutput.put(depStepId, depTask.get().getOutput());
                }
            }

            if (allDependenciesMet) {
                log.info("All dependencies met for step '{}', creating task", stepId);
                createNextTask(request, stepDef, mergedOutput);
            }
        }
    }

    /**
     * Create a task for a next step, merging input from the completed task.
     */
    @SuppressWarnings("unchecked")
    private void createNextTask(WorkflowRequest request, Map<String, Object> stepDef, WorkflowTask previousTask) {
        // Merge workflow input with previous task output
        Map<String, Object> taskInput = new HashMap<>(request.getInput());
        if (previousTask.getOutput() != null) {
            taskInput.put("previousStepOutput", previousTask.getOutput());
        }
        taskInput.put("previousStepId", previousTask.getStepId());

        createNextTask(request, stepDef, taskInput);
    }

    /**
     * Create a task for a step with the given input.
     */
    @SuppressWarnings("unchecked")
    private void createNextTask(WorkflowRequest request, Map<String, Object> stepDef, Map<String, Object> input) {
        String stepId = (String) stepDef.get("id");
        String agentProfileIdStr = (String) stepDef.get("agentProfileId");

        if (agentProfileIdStr == null) {
            log.error("Step '{}' has no agentProfileId", stepId);
            return;
        }

        UUID agentProfileId = UUID.fromString(agentProfileIdStr);
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("AgentProfile", agentProfileIdStr));

        // Compute next attempt number (handles re-execution in loops like review→generate)
        int nextAttempt = taskRepository.findByRequestIdAndStepId(request.getId(), stepId).stream()
                .mapToInt(WorkflowTask::getAttempt)
                .max()
                .orElse(0) + 1;

        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId(stepId);
        task.setAgentProfile(profile);
        task.setInput(input);
        task.setStatus(TaskStatus.PENDING);
        task.setAttempt(nextAttempt);

        taskRepository.save(task);
        log.info("Created task for step '{}' (attempt {}) in request {}", stepId, nextAttempt, request.getId());
    }

    /**
     * Check if the workflow request is complete.
     * A workflow is complete when all terminal steps (steps not depended on by others) have finished.
     */
    @SuppressWarnings("unchecked")
    private void checkWorkflowCompletion(WorkflowRequest request, Workflow workflow) {
        List<Map<String, Object>> steps = workflow.getSteps();

        // Find terminal step IDs (not referenced in any other step's dependsOn or transitions)
        Set<String> allStepIds = new HashSet<>();
        Set<String> referencedStepIds = new HashSet<>();

        for (Map<String, Object> step : steps) {
            String stepId = (String) step.get("id");
            allStepIds.add(stepId);

            List<String> dependsOn = (List<String>) step.get("dependsOn");
            if (dependsOn != null) {
                referencedStepIds.addAll(dependsOn);
            }

            Map<String, String> transitions = (Map<String, String>) step.get("transitions");
            if (transitions != null) {
                referencedStepIds.addAll(transitions.values());
            }
        }

        Set<String> terminalStepIds = new HashSet<>(allStepIds);
        terminalStepIds.removeAll(referencedStepIds);

        if (terminalStepIds.isEmpty()) {
            // All steps are referenced — possible loop. Check if any task is still pending/running
            List<WorkflowTask> activeTasks = taskRepository.findByRequestId(request.getId()).stream()
                    .filter(t -> t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.RUNNING)
                    .toList();

            if (activeTasks.isEmpty()) {
                // No active tasks and no terminal steps — look for the last completed task
                completeWorkflow(request);
            }
            return;
        }

        // Check if all terminal steps have completed successfully
        for (String terminalStepId : terminalStepIds) {
            List<WorkflowTask> tasks = taskRepository.findByRequestId(request.getId()).stream()
                    .filter(t -> terminalStepId.equals(t.getStepId()))
                    .toList();

            boolean terminalDone = tasks.stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.COMPLETED && t.getResult() == TaskResult.SUCCESS);

            if (!terminalDone) {
                return; // Not all terminal steps done yet
            }
        }

        // All terminal steps completed
        completeWorkflow(request);
    }

    /**
     * Mark a workflow request as completed.
     * Collects output from all completed tasks.
     */
    private void completeWorkflow(WorkflowRequest request) {
        // Collect outputs from all completed tasks
        List<WorkflowTask> completedTasks = taskRepository.findByRequestIdAndStatus(
                request.getId(), TaskStatus.COMPLETED);

        Map<String, Object> output = new HashMap<>();
        for (WorkflowTask task : completedTasks) {
            if (task.getOutput() != null) {
                output.put(task.getStepId(), task.getOutput());
            }
        }

        request.setOutput(output);
        request.setStatus(RequestStatus.COMPLETED);
        request.setCompletedAt(java.time.Instant.now());
        requestRepository.save(request);

        log.info("Workflow request {} completed with {} step outputs",
                request.getId(), output.size());
    }

    /**
     * Find a step definition by ID.
     */
    private Map<String, Object> findStepDef(List<Map<String, Object>> steps, String stepId) {
        return steps.stream()
                .filter(s -> stepId.equals(s.get("id")))
                .findFirst()
                .orElse(null);
    }
}
