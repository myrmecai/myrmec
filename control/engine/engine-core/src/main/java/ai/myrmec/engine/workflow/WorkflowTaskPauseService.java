// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.workflow.dto.WorkflowTaskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Service for continuing or stopping paused workflow tasks (J3 pause gate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTaskPauseService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowRequestRepository requestRepository;
    private final WorkflowProgressionService progressionService;
    private final WorkflowTaskService taskService;

    /**
     * Continue a paused task.
     *
     * <p>If the task is in PAUSED_BEFORE state, clear the pause fields,
     * set status to PENDING so the dispatcher picks it up, and restore
     * the request to RUNNING.</p>
     *
     * <p>If the task is in PAUSED_AFTER state, clear the pause fields
     * and call {@link WorkflowProgressionService#onTaskCompleted} to
     * create downstream tasks, then restore the request to RUNNING.</p>
     */
    @Transactional
    public WorkflowTaskResponse continueTask(UUID taskId) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        if (task.getStatus() != TaskStatus.PAUSED) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not paused (status=" + task.getStatus() + ")");
        }

        String pauseState = task.getPauseState();
        task.setPausedAt(null);
        task.setPausedByUserId(null);
        task.setPauseReason(null);

        if ("PAUSED_BEFORE".equals(pauseState)) {
            // Resume: set back to PENDING so the dispatcher picks it up.
            // Use "RESUMED_BEFORE" so shouldPauseAfter knows the BEFORE gate
            // was cleared but the AFTER gate has not yet fired.
            task.setPauseState("RESUMED_BEFORE");
            task.setStatus(TaskStatus.PENDING);
            log.info("Task {} continued from PAUSED_BEFORE — set to PENDING for dispatch", taskId);
        } else if ("PAUSED_AFTER".equals(pauseState)) {
            // Resume: proceed with downstream task creation.
            // Use "RESUMED_AFTER" so shouldPauseAfter knows the AFTER gate
            // was already fired and cleared — don't re-pause.
            task.setPauseState("RESUMED_AFTER");
            task.setStatus(TaskStatus.COMPLETED);
            taskRepository.save(task);
            log.info("Task {} continued from PAUSED_AFTER — progressing workflow", taskId);
            // Restore request to RUNNING before progression
            restoreRequestToRunning(task);
            // Now create downstream tasks
            progressionService.onTaskCompleted(task);
            return taskService.findById(taskId);
        } else {
            throw new IllegalStateException(
                    "Task " + taskId + " has unknown pauseState: " + pauseState);
        }

        taskRepository.save(task);
        restoreRequestToRunning(task);
        return taskService.findById(taskId);
    }

    /**
     * Stop a paused task — mark it as FAILED and fail the workflow.
     */
    @Transactional
    public WorkflowTaskResponse stopTask(UUID taskId, String reason) {
        WorkflowTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId.toString()));

        if (task.getStatus() != TaskStatus.PAUSED) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not paused (status=" + task.getStatus() + ")");
        }

        task.setStatus(TaskStatus.COMPLETED);
        task.setResult(TaskResult.FAILURE);
        task.setErrorMessage("Stopped by operator: " + reason);
        task.setCompletedAt(Instant.now());
        task.setPauseState("STOPPED");
        taskRepository.save(task);

        log.info("Task {} stopped by operator — reason: {}", taskId, reason);

        // Fail the workflow request
        progressionService.onTaskFailed(task);

        return taskService.findById(taskId);
    }

    /**
     * Restore the request to RUNNING if no other tasks are still PAUSED.
     */
    private void restoreRequestToRunning(WorkflowTask task) {
        WorkflowRequest request = task.getRequest();
        if (request.getStatus() == RequestStatus.PAUSED) {
            // Check if any other tasks in this request are still paused
            boolean anyPaused = taskRepository.findByRequestId(request.getId()).stream()
                    .anyMatch(t -> !t.getId().equals(task.getId())
                            && t.getStatus() == TaskStatus.PAUSED);
            if (!anyPaused) {
                request.setStatus(RequestStatus.RUNNING);
                requestRepository.save(request);
                log.info("Request {} restored to RUNNING", request.getId());
            }
        }
    }
}