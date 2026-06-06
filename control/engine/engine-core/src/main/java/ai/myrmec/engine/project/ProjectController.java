package ai.myrmec.engine.project;

import ai.myrmec.engine.project.dto.CreateProjectRequest;
import ai.myrmec.engine.project.dto.MoveProjectGroupRequest;
import ai.myrmec.engine.project.dto.ProjectResponse;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for project management.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Project management operations")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    @Operation(summary = "List all projects")
    public ResponseEntity<List<ProjectResponse>> listProjects() {
        List<Project> projects = projectService.findAll();
        List<ProjectResponse> response = projects.stream()
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project by ID")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable UUID id) {
        Project project = projectService.findById(id);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @PostMapping
    @Operation(summary = "Create a new project")
    @PreAuthorize("hasRole('ORG_ADMIN') or (#request.groupId != null and @groupAccess.canEdit(#request.groupId, authentication))")
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request) {

        Project project = projectService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectResponse.from(project));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update project details")
    @PreAuthorize("@projectAccess.canEdit(#id, authentication)")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectRequest request) {

        Project project = projectService.update(id, request);

        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a project")
    @PreAuthorize("@projectAccess.canOwn(#id, authentication)")
    public ResponseEntity<Void> deleteProject(@PathVariable UUID id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/move-group")
    @Operation(summary = "Move a project to a different group")
    @PreAuthorize("@projectAccess.canOwn(#id, authentication) and @groupAccess.canEdit(#request.groupId, authentication)")
    public ResponseEntity<ProjectResponse> moveProjectToGroup(
            @PathVariable UUID id,
            @Valid @RequestBody MoveProjectGroupRequest request) {

        Project project = projectService.moveToGroup(id, request.getGroupId());
        return ResponseEntity.ok(ProjectResponse.from(project));
    }
}
