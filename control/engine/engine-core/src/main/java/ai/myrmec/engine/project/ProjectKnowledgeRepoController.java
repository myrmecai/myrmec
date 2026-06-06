package ai.myrmec.engine.project;

import ai.myrmec.engine.project.dto.ProjectKnowledgeRepoRequest;
import ai.myrmec.engine.project.dto.ProjectKnowledgeRepoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing knowledge repos attached to a project.
 *
 * Knowledge repos are pure configuration; their contents are fetched on
 * demand at task dispatch time and never persisted to the database.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge-repos")
@RequiredArgsConstructor
@Tag(name = "Project Knowledge Repos", description = "Manage knowledge source repositories attached to a project")
public class ProjectKnowledgeRepoController {

    private final ProjectKnowledgeRepoService service;

    @GetMapping
    @Operation(summary = "List knowledge repos for a project")
    public ResponseEntity<List<ProjectKnowledgeRepoResponse>> list(@PathVariable UUID projectId) {
        List<ProjectKnowledgeRepoResponse> response = service.list(projectId).stream()
                .map(ProjectKnowledgeRepoResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a knowledge repo")
    public ResponseEntity<ProjectKnowledgeRepoResponse> get(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ProjectKnowledgeRepoResponse.from(service.get(projectId, id)));
    }

    @PostMapping
    @Operation(summary = "Add a knowledge repo to a project")
    public ResponseEntity<ProjectKnowledgeRepoResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectKnowledgeRepoRequest request) {
        ProjectKnowledgeRepo created = service.create(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectKnowledgeRepoResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a knowledge repo")
    public ResponseEntity<ProjectKnowledgeRepoResponse> update(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody ProjectKnowledgeRepoRequest request) {
        ProjectKnowledgeRepo updated = service.update(projectId, id, request);
        return ResponseEntity.ok(ProjectKnowledgeRepoResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a knowledge repo")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        service.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }
}
