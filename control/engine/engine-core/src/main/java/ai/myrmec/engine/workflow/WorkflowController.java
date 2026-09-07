package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.*;
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
 * REST endpoints for managing workflows.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public List<WorkflowResponse> findByProject(@PathVariable UUID projectId) {
        return workflowService.findByProject(projectId);
    }

    @GetMapping("/published")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public List<WorkflowResponse> findPublishedByProject(@PathVariable UUID projectId) {
        return workflowService.findPublishedByProject(projectId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public WorkflowResponse findById(@PathVariable UUID projectId, @PathVariable UUID id) {
        WorkflowResponse workflow = workflowService.findById(id);
        // Ensure workflow belongs to the project
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflow;
    }

    @PostMapping
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication) and @projectAccess.allowsServiceType(#projectId, 'WORKFLOW')")
    public ResponseEntity<WorkflowResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Ensure path projectId matches request
        if (!projectId.equals(request.projectId())) {
            return ResponseEntity.badRequest().build();
        }

        UUID userId = principal.getUserId();
        WorkflowResponse response = workflowService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflowService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public WorkflowResponse publish(@PathVariable UUID projectId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflowService.publish(id);
    }

    /**
     * Feature 10 (§16.1): the explicit publication binding from the
     * workflow-local agentProfileCode alias to an Agent Profile UUID.
     * Required before publishing a workflow with ORCHESTRATOR steps.
     */
    @PostMapping("/{id}/orchestration-bindings")
    public WorkflowResponse setOrchestrationBindings(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, UUID> bindings) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflowService.setOrchestrationBindings(id, bindings);
    }

    @PostMapping("/{id}/archive")
    public WorkflowResponse archive(@PathVariable UUID projectId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflowService.archive(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
