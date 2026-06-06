package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.StartWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowRequestResponse;
import ai.myrmec.engine.workflow.dto.WorkflowTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ai.myrmec.engine.user.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for managing workflow execution requests.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows/{workflowId}/requests")
@RequiredArgsConstructor
public class WorkflowRequestController {

    private final WorkflowRequestService requestService;
    private final WorkflowTaskService taskService;
    private final WorkflowService workflowService;

    @GetMapping
    public List<WorkflowRequestResponse> findByWorkflow(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId) {
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return requestService.findByWorkflow(workflowId);
    }

    @GetMapping("/{requestId}")
    public WorkflowRequestResponse findById(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return requestService.findById(requestId);
    }

    @GetMapping("/{requestId}/tasks")
    public List<WorkflowTaskResponse> findTasks(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return taskService.findByRequest(requestId);
    }

    @PostMapping
    public ResponseEntity<WorkflowRequestResponse> start(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @Valid @RequestBody StartWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Verify workflowId matches
        if (!workflowId.equals(request.workflowId())) {
            return ResponseEntity.badRequest().build();
        }

        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }

        UUID userId = principal.getUserId();
        WorkflowRequestResponse response = requestService.start(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{requestId}/cancel")
    public WorkflowRequestResponse cancel(
            @PathVariable UUID projectId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to project
        var workflow = workflowService.findById(workflowId);
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return requestService.cancel(requestId);
    }
}
