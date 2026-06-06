package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.workflow.dto.TaskAttemptResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing task attempts lifecycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskAttemptService {

    private final TaskAttemptRepository taskAttemptRepository;
    private final WorkflowTaskRepository workflowTaskRepository;
    private final ExecutionEventRepository executionEventRepository;
    private final WorkflowProgressionService workflowProgressionService;
    private final TaskMetricsAggregator taskMetricsAggregator;

    /**
     * Create a new attempt for a task.
     */
    @Transactional
    public TaskAttempt createAttempt(WorkflowTask task, AgentInstance agentInstance) {
        TaskAttempt attempt = new TaskAttempt();
        attempt.setTask(task);
        attempt.setAttemptNumber(task.getAttempt());
        attempt.setAgentInstance(agentInstance);
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setStartedAt(Instant.now());
        
        attempt = taskAttemptRepository.save(attempt);
        
        // Update task's current attempt reference
        task.setCurrentAttempt(attempt);
        task.getAttempts().add(attempt);
        workflowTaskRepository.save(task);
        
        log.debug("Created attempt {} for task {}", attempt.getAttemptNumber(), task.getId());
        return attempt;
    }

    /**
     * Mark an attempt as completed with success.
     */
    @Transactional
    public TaskAttempt completeSuccess(UUID attemptId, Map<String, Object> output) {
        TaskAttempt attempt = getAttempt(attemptId);
        attempt.markSuccess(output);
        
        // Also update the legacy task fields for backward compatibility
        WorkflowTask task = attempt.getTask();
        task.setOutput(output);
        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.SUCCESS);
        task.setCompletedAt(Instant.now());
        workflowTaskRepository.save(task);

        // Aggregate observability metrics (tokens, timing, cost) for this attempt.
        try {
            taskMetricsAggregator.aggregateAndPersist(task, attemptId);
        } catch (Exception e) {
            log.warn("Failed to aggregate metrics for task {} attempt {}: {}",
                    task.getId(), attemptId, e.getMessage());
        }

        log.debug("Attempt {} completed with SUCCESS", attemptId);
        attempt = taskAttemptRepository.save(attempt);
        
        // Trigger workflow progression to create next step tasks
        workflowProgressionService.onTaskCompleted(task);
        
        return attempt;
    }

    /**
     * Mark an attempt as failed.
     */
    @Transactional
    public TaskAttempt completeFailed(UUID attemptId, String errorMessage, String errorCode) {
        TaskAttempt attempt = getAttempt(attemptId);
        attempt.markFailed(errorMessage, errorCode);
        
        // Update legacy task fields
        WorkflowTask task = attempt.getTask();
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(Instant.now());
        // Note: task status/result updated by retry logic in TaskDispatcherService
        workflowTaskRepository.save(task);

        // Aggregate observability metrics even on failure.
        try {
            taskMetricsAggregator.aggregateAndPersist(task, attemptId);
        } catch (Exception e) {
            log.warn("Failed to aggregate metrics for failed task {} attempt {}: {}",
                    task.getId(), attemptId, e.getMessage());
        }

        log.debug("Attempt {} completed with FAILURE: {}", attemptId, errorCode);
        return taskAttemptRepository.save(attempt);
    }

    /**
     * Mark an attempt as abandoned (e.g., agent disconnected).
     */
    @Transactional
    public TaskAttempt markAbandoned(UUID attemptId, String reason) {
        TaskAttempt attempt = getAttempt(attemptId);
        attempt.markAbandoned(reason);
        
        log.debug("Attempt {} marked as ABANDONED: {}", attemptId, reason);
        return taskAttemptRepository.save(attempt);
    }

    /**
     * Mark an attempt as skipped.
     */
    @Transactional
    public TaskAttempt markSkipped(UUID attemptId, String reason) {
        TaskAttempt attempt = getAttempt(attemptId);
        attempt.markSkipped(reason);
        
        log.debug("Attempt {} marked as SKIPPED: {}", attemptId, reason);
        return taskAttemptRepository.save(attempt);
    }

    /**
     * Get all attempts for a task.
     */
    @Transactional(readOnly = true)
    public List<TaskAttemptResponse> getAttempts(UUID taskId) {
        return taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(taskId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get a specific attempt.
     */
    @Transactional(readOnly = true)
    public TaskAttemptResponse getAttemptResponse(UUID attemptId) {
        return toResponse(getAttempt(attemptId));
    }

    /**
     * Get the current/latest attempt for a task.
     */
    @Transactional(readOnly = true)
    public TaskAttemptResponse getCurrentAttempt(UUID taskId) {
        return taskAttemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(taskId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Find the current attempt ID for a task.
     * Used for backward compatibility when agent doesn't provide attemptId.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findCurrentAttemptId(UUID taskId) {
        return taskAttemptRepository.findFirstByTaskIdOrderByAttemptNumberDesc(taskId)
                .map(TaskAttempt::getId);
    }

    /**
     * Count retryable attempts (those that count toward retry limit).
     */
    @Transactional(readOnly = true)
    public int countRetryableAttempts(UUID taskId) {
        return taskAttemptRepository.countRetryableAttempts(taskId);
    }

    /**
     * Check if task can be retried based on max attempts.
     */
    @Transactional(readOnly = true)
    public boolean canRetry(UUID taskId, int maxAttempts) {
        int retryableCount = countRetryableAttempts(taskId);
        return retryableCount < maxAttempts;
    }

    /**
     * Find all running attempts for an agent instance.
     */
    @Transactional(readOnly = true)
    public List<TaskAttempt> findRunningAttemptsByAgent(UUID agentInstanceId) {
        return taskAttemptRepository.findByAgentInstanceIdAndStatus(agentInstanceId, AttemptStatus.RUNNING);
    }

    /**
     * Mark all running attempts for an agent as abandoned.
     * Used when an agent disconnects unexpectedly.
     */
    @Transactional
    public int abandonAllForAgent(UUID agentInstanceId, String reason) {
        List<TaskAttempt> runningAttempts = findRunningAttemptsByAgent(agentInstanceId);
        for (TaskAttempt attempt : runningAttempts) {
            attempt.markAbandoned(reason);
            taskAttemptRepository.save(attempt);
        }
        if (!runningAttempts.isEmpty()) {
            log.info("Abandoned {} running attempts for agent {}", runningAttempts.size(), agentInstanceId);
        }
        return runningAttempts.size();
    }

    private TaskAttempt getAttempt(UUID attemptId) {
        return taskAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskAttempt", attemptId.toString()));
    }

    private TaskAttemptResponse toResponse(TaskAttempt attempt) {
        return new TaskAttemptResponse(
                attempt.getId(),
                attempt.getTask().getId(),
                attempt.getAttemptNumber(),
                attempt.getAgentInstance() != null ? attempt.getAgentInstance().getId() : null,
                attempt.getStatus(),
                attempt.getResult(),
                attempt.getOutput(),
                attempt.getErrorMessage(),
                attempt.getErrorCode(),
                attempt.getStartedAt(),
                attempt.getCompletedAt()
        );
    }
}
