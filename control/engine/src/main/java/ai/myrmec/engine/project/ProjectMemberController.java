package ai.myrmec.engine.project;

import ai.myrmec.engine.project.dto.AssignProjectMemberRequest;
import ai.myrmec.engine.project.dto.ProjectMemberCandidate;
import ai.myrmec.engine.project.dto.ProjectMemberListResponse;
import ai.myrmec.engine.project.dto.ProjectMemberResponse;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manage project-scoped user memberships — viewers/editors of the project (or SYSTEM_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ProjectMemberListResponse list(@PathVariable UUID projectId) {
        return projectMemberService.list(projectId);
    }

    @GetMapping("/candidates")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public List<ProjectMemberCandidate> listCandidates(@PathVariable UUID projectId) {
        return projectMemberService.listCandidates(projectId);
    }

    @PostMapping
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<ProjectMemberResponse> assign(
            @PathVariable UUID projectId,
            @Valid @RequestBody AssignProjectMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ProjectMemberResponse response = projectMemberService.assign(projectId, request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<Void> remove(
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        projectMemberService.remove(projectId, userId, roleId);
        return ResponseEntity.noContent().build();
    }
}
