package ai.myrmec.engine._controllers.workflow;

import ai.myrmec.engine.workflow.WorkflowService;
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
@RequestMapping("/api/v1/teams/{teamId}/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public List<WorkflowResponse> findByTeam(@PathVariable UUID teamId) {
        return workflowService.findByTeam(teamId);
    }

    @GetMapping("/published")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public List<WorkflowResponse> findPublishedByTeam(@PathVariable UUID teamId) {
        return workflowService.findPublishedByTeam(teamId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public WorkflowResponse findById(@PathVariable UUID teamId, @PathVariable UUID id) {
        WorkflowResponse workflow = workflowService.findById(id);
        // Ensure workflow belongs to the Team
        if (!workflow.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return workflow;
    }

    @PostMapping
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication) and @teamAccess.allowsServiceType(#teamId, 'WORKFLOW')")
    public ResponseEntity<WorkflowResponse> create(
            @PathVariable UUID teamId,
            @Valid @RequestBody CreateWorkflowRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        // Ensure path teamId matches request
        if (!teamId.equals(request.teamId())) {
            return ResponseEntity.badRequest().build();
        }

        UUID userId = principal.getUserId();
        WorkflowResponse response = workflowService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(
            @PathVariable UUID teamId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return workflowService.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public WorkflowResponse publish(@PathVariable UUID teamId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
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
            @PathVariable UUID teamId,
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, UUID> bindings) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return workflowService.setOrchestrationBindings(id, bindings);
    }

    @PostMapping("/{id}/archive")
    public WorkflowResponse archive(@PathVariable UUID teamId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        return workflowService.archive(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID teamId, @PathVariable UUID id) {
        WorkflowResponse existing = workflowService.findById(id);
        if (!existing.teamId().equals(teamId)) {
            throw new IllegalArgumentException("Workflow does not belong to this Team");
        }
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
