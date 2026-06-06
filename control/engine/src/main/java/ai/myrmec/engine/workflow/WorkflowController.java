package ai.myrmec.engine.workflow;

import ai.myrmec.engine.workflow.dto.*;
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
 * REST endpoints for managing workflows.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public List<WorkflowResponse> findByProject(@PathVariable UUID projectId) {
        return workflowService.findByProject(projectId);
    }

    @GetMapping("/published")
    public List<WorkflowResponse> findPublishedByProject(@PathVariable UUID projectId) {
        return workflowService.findPublishedByProject(projectId);
    }

    @GetMapping("/{id}")
    public WorkflowResponse findById(@PathVariable UUID projectId, @PathVariable UUID id) {
        WorkflowResponse workflow = workflowService.findById(id);
        // Ensure workflow belongs to the project
        if (!workflow.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Workflow does not belong to this project");
        }
        return workflow;
    }

    @PostMapping
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
