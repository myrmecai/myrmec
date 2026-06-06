package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.ExecutionEventResponse;
import ai.myrmec.engine.workflow.dto.TaskAttemptResponse;
import ai.myrmec.engine.workflow.dto.WorkflowTaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for workflow tasks and attempts.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final WorkflowTaskService taskService;
    private final TaskAttemptService attemptService;
    private final ExecutionEventService eventService;

    /**
     * Get a task by ID.
     */
    @GetMapping("/{taskId}")
    public WorkflowTaskResponse getTask(@PathVariable UUID taskId) {
        return taskService.findById(taskId);
    }

    /**
     * Get all attempts for a task.
     */
    @GetMapping("/{taskId}/attempts")
    public List<TaskAttemptResponse> getAttempts(@PathVariable UUID taskId) {
        return attemptService.getAttempts(taskId);
    }

    /**
     * Get the current/latest attempt for a task.
     */
    @GetMapping("/{taskId}/attempts/current")
    public TaskAttemptResponse getCurrentAttempt(@PathVariable UUID taskId) {
        return attemptService.getCurrentAttempt(taskId);
    }

    /**
     * Get a specific attempt.
     */
    @GetMapping("/{taskId}/attempts/{attemptId}")
    public TaskAttemptResponse getAttempt(
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId) {
        return attemptService.getAttemptResponse(attemptId);
    }

    /**
     * Get execution events for a task (all attempts combined).
     */
    @GetMapping("/{taskId}/events")
    public List<ExecutionEventResponse> getTaskEvents(@PathVariable UUID taskId) {
        return eventService.findByTaskId(taskId);
    }

    /**
     * Get execution events for a specific attempt.
     */
    @GetMapping("/{taskId}/attempts/{attemptId}/events")
    public List<ExecutionEventResponse> getAttemptEvents(
            @PathVariable UUID taskId,
            @PathVariable UUID attemptId) {
        return eventService.findByAttemptId(attemptId);
    }
}
