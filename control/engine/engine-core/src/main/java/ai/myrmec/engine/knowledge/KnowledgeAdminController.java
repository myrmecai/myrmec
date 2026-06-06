package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.KnowledgeDocumentRequest;
import ai.myrmec.engine.knowledge.dto.KnowledgeDocumentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin controller for organization-level knowledge documents.
 * Requires SYSTEM_ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeAdminController {

    private final KnowledgeDocumentService service;

    /**
     * List all organization-level knowledge documents.
     */
    @GetMapping
    public ResponseEntity<List<KnowledgeDocumentResponse>> listOrganizationDocuments(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        List<KnowledgeDocument> docs = activeOnly
                ? service.findActiveOrganizationDocuments()
                : service.findOrganizationDocuments();
        return ResponseEntity.ok(docs.stream().map(KnowledgeDocumentResponse::from).toList());
    }

    /**
     * Get a specific organization knowledge document.
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocumentResponse> getDocument(@PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        // Verify it's an organization document
        if (doc.getScope() != KnowledgeScope.ORGANIZATION) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(doc));
    }

    /**
     * Create a new organization-level knowledge document.
     */
    @PostMapping
    public ResponseEntity<KnowledgeDocumentResponse> createDocument(
            @Valid @RequestBody KnowledgeDocumentRequest request,
            @CurrentUser UUID userId) {
        KnowledgeDocument doc = service.createOrganizationDocument(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeDocumentResponse.from(doc));
    }

    /**
     * Update an organization knowledge document.
     */
    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeDocumentResponse> updateDocument(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        KnowledgeDocument doc = service.findById(id);
        if (doc.getScope() != KnowledgeScope.ORGANIZATION) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.update(id, request);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Activate an organization knowledge document.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<KnowledgeDocumentResponse> activateDocument(@PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (doc.getScope() != KnowledgeScope.ORGANIZATION) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.setActive(id, true);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Deactivate an organization knowledge document.
     */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<KnowledgeDocumentResponse> deactivateDocument(@PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (doc.getScope() != KnowledgeScope.ORGANIZATION) {
            return ResponseEntity.notFound().build();
        }
        KnowledgeDocument updated = service.setActive(id, false);
        return ResponseEntity.ok(KnowledgeDocumentResponse.from(updated));
    }

    /**
     * Delete an organization knowledge document.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        KnowledgeDocument doc = service.findById(id);
        if (doc.getScope() != KnowledgeScope.ORGANIZATION) {
            return ResponseEntity.notFound().build();
        }
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
