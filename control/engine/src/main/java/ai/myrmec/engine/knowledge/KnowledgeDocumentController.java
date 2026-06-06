package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.KnowledgeDocumentRequest;
import ai.myrmec.engine.knowledge.dto.KnowledgeDocumentResponse;
import ai.myrmec.engine.knowledge.dto.ResolveContextRequest;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controller for project-scoped knowledge documents.
 * Accessible by any authenticated user with project access.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService service;
    private final TaskContextResolver taskContextResolver;

    /**
     * List all knowledge documents for a project.
     */
    @GetMapping
    public ResponseEntity<List<KnowledgeDocumentResponse>> listProjectDocuments(
            @PathVariable UUID projectId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        List<KnowledgeDocument> docs = activeOnly
                ? service.findActiveProjectDocuments(projectId)
                : service.findProjectDocuments(projectId);
        return ResponseEntity.ok(docs.stream().map(KnowledgeDocumentResponse::from).toList());
    }

    /**
     * Get a specific knowledge document.
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocumentResponse> getDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        // Verify it belongs to this project
        if (!projectId.equals(doc.getProjectId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(doc));
    }

    /**
     * Create a new project knowledge document.
     */
    @PostMapping
    public ResponseEntity<KnowledgeDocumentResponse> createDocument(
            @PathVariable UUID projectId,
            @Valid @RequestBody KnowledgeDocumentRequest request,
            @CurrentUser UUID userId) {
        KnowledgeDocument doc = service.createProjectDocument(projectId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeDocumentResponse.from(doc));
    }

    /**
     * Update a knowledge document.
     */
    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeDocumentResponse> updateDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        KnowledgeDocument doc = service.findById(id);
        // Verify it belongs to this project
        if (!projectId.equals(doc.getProjectId())) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.update(id, request);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Activate a knowledge document.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<KnowledgeDocumentResponse> activateDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (!projectId.equals(doc.getProjectId())) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.setActive(id, true);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Deactivate a knowledge document.
     */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<KnowledgeDocumentResponse> deactivateDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (!projectId.equals(doc.getProjectId())) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.setActive(id, false);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Delete a knowledge document.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID projectId,
            @PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (!projectId.equals(doc.getProjectId())) {
            return ResponseEntity.notFound().build();
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolve knowledge context for a project.
     * Returns matching knowledge documents filtered by file path patterns.
     */
    @PostMapping("/resolve")
    public ResponseEntity<TaskContext> resolveContext(
            @PathVariable UUID projectId,
            @RequestBody(required = false) ResolveContextRequest request) {
        String filePath = request != null ? request.filePath() : null;
        Integer charBudget = request != null ? request.charBudget() : null;
        
        TaskContext context = taskContextResolver.resolve(
                projectId,
                filePath,
                null,  // no workflow artifacts repo
                charBudget != null ? charBudget : 32000
        );
        return ResponseEntity.ok(context);
    }
}
