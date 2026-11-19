package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.StartWorkflowRequest;
import ai.myrmec.engine.workflow.dto.WorkflowRequestExecutionResponse;
import ai.myrmec.engine.workflow.dto.WorkflowRequestResponse;
import ai.myrmec.engine.workflow.dto.WorkflowTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ai.myrmec.engine.user.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for managing workflow execution requests.
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/workflows/{workflowId}/requests")
@RequiredArgsConstructor
public class WorkflowRequestController {

    private final WorkflowRequestService requestService;
    private final WorkflowTaskService taskService;
    private final WorkflowRequestExecutionService executionService;
    private final WorkflowService workflowService;

    @GetMapping
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public List<WorkflowRequestResponse> findByWorkflow(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId) {
        // Verify workflow belongs to Team
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return requestService.findByWorkflow(workflowId);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public WorkflowRequestResponse findById(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to Team
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return requestService.findById(requestId);
    }

    @GetMapping("/{requestId}/tasks")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public List<WorkflowTaskResponse> findTasks(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to Team
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return taskService.findByRequest(requestId);
    }

    @PostMapping
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication)")
    public ResponseEntity<WorkflowRequestResponse> start(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId,
            @Valid @RequestBody StartWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Verify workflowId matches
        if (!workflowId.equals(request.workflowId())) {
            return ResponseEntity.badRequest().build();
        }

        // Verify workflow belongs to Team
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }

        UUID userId = principal.getUserId();
        WorkflowRequestResponse response = requestService.start(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{requestId}/cancel")
    public WorkflowRequestResponse cancel(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        // Verify workflow belongs to Team
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return requestService.cancel(requestId);
    }

    /**
     * Resolve a run to the active task/attempt/execution chain. The VS Code
     * plugin uses this to discover the §22 interaction endpoints.
     */
    @GetMapping("/{requestId}/executions/current")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public ResponseEntity<WorkflowRequestExecutionResponse> getCurrentExecution(
            @PathVariable UUID teamId,
            @PathVariable UUID workflowId,
            @PathVariable UUID requestId) {
        var workflow = workflowService.findById(workflowId);
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return executionService.findCurrentExecution(teamId, workflowId, requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
