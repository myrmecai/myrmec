package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.knowledge.dto.KnowledgeDocumentRequest;
import ai.myrmec.engine.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing knowledge documents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentService {

    private final KnowledgeDocumentRepository repository;
    private final ProjectRepository projectRepository;

    /**
     * Get all organization-level knowledge documents.
     */
    public List<KnowledgeDocument> findOrganizationDocuments() {
        log.debug("Finding all organization-level knowledge documents");
        return repository.findByScopeOrderByPriorityDesc(KnowledgeScope.ORGANIZATION);
    }

    /**
     * Get active organization-level knowledge documents.
     */
    public List<KnowledgeDocument> findActiveOrganizationDocuments() {
        log.debug("Finding active organization-level knowledge documents");
        return repository.findByScopeAndActiveOrderByPriorityDesc(KnowledgeScope.ORGANIZATION, true);
    }

    /**
     * Get all project-level knowledge documents.
     */
    public List<KnowledgeDocument> findProjectDocuments(UUID projectId) {
        log.debug("Finding all knowledge documents for project: {}", projectId);
        validateProjectExists(projectId);
        return repository.findByProjectIdOrderByPriorityDesc(projectId);
    }

    /**
     * Get active project-level knowledge documents.
     */
    public List<KnowledgeDocument> findActiveProjectDocuments(UUID projectId) {
        log.debug("Finding active knowledge documents for project: {}", projectId);
        validateProjectExists(projectId);
        return repository.findByProjectIdAndActiveOrderByPriorityDesc(projectId, true);
    }

    /**
     * Get knowledge document by ID.
     */
    public KnowledgeDocument findById(UUID id) {
        log.debug("Finding knowledge document by ID: {}", id);
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeDocument", id.toString()));
    }

    /**
     * Create organization-level knowledge document.
     */
    @Transactional
    public KnowledgeDocument createOrganizationDocument(KnowledgeDocumentRequest request, UUID createdBy) {
        log.debug("Creating organization knowledge document: {}", request.name());

        // Check uniqueness
        if (repository.findByScopeAndProjectIdAndName(KnowledgeScope.ORGANIZATION, null, request.name()).isPresent()) {
            throw new DuplicateResourceException("KnowledgeDocument", "name", request.name());
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setScope(KnowledgeScope.ORGANIZATION);
        doc.setProjectId(null);
        applyRequest(doc, request);
        doc.setCreatedBy(createdBy);

        KnowledgeDocument saved = repository.save(doc);
        log.info("Created organization knowledge document: {} (id: {})", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * Create project-level knowledge document.
     */
    @Transactional
    public KnowledgeDocument createProjectDocument(UUID projectId, KnowledgeDocumentRequest request, UUID createdBy) {
        log.debug("Creating project knowledge document: {} for project: {}", request.name(), projectId);

        validateProjectExists(projectId);

        // Check uniqueness
        if (repository.findByScopeAndProjectIdAndName(KnowledgeScope.PROJECT, projectId, request.name()).isPresent()) {
            throw new DuplicateResourceException("KnowledgeDocument", "name", request.name());
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setScope(KnowledgeScope.PROJECT);
        doc.setProjectId(projectId);
        applyRequest(doc, request);
        doc.setCreatedBy(createdBy);

        KnowledgeDocument saved = repository.save(doc);
        log.info("Created project knowledge document: {} (id: {}, project: {})", saved.getName(), saved.getId(), projectId);
        return saved;
    }

    /**
     * Update knowledge document.
     */
    @Transactional
    public KnowledgeDocument update(UUID id, KnowledgeDocumentRequest request) {
        log.debug("Updating knowledge document: {}", id);

        KnowledgeDocument doc = findById(id);

        // Check uniqueness if name changed
        if (!doc.getName().equals(request.name())) {
            if (repository.existsByScopeAndProjectIdAndNameAndIdNot(doc.getScope(), doc.getProjectId(), request.name(), id)) {
                throw new DuplicateResourceException("KnowledgeDocument", "name", request.name());
            }
        }

        applyRequest(doc, request);
        KnowledgeDocument saved = repository.save(doc);
        log.info("Updated knowledge document: {} (id: {})", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * Activate/deactivate knowledge document.
     */
    @Transactional
    public KnowledgeDocument setActive(UUID id, boolean active) {
        log.debug("Setting knowledge document {} active: {}", id, active);

        KnowledgeDocument doc = findById(id);
        doc.setActive(active);
        KnowledgeDocument saved = repository.save(doc);

        log.info("{} knowledge document: {} (id: {})", active ? "Activated" : "Deactivated", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * Delete knowledge document.
     */
    @Transactional
    public void delete(UUID id) {
        log.debug("Deleting knowledge document: {}", id);

        KnowledgeDocument doc = findById(id);
        repository.delete(doc);

        log.info("Deleted knowledge document: {} (id: {})", doc.getName(), id);
    }

    /**
     * Get all documents for context resolution (org + project).
     * Returns documents ordered by priority (highest first).
     */
    public List<KnowledgeDocument> findForContextResolution(UUID projectId) {
        log.debug("Finding knowledge documents for context resolution, project: {}", projectId);
        return repository.findForContextResolution(projectId);
    }

    /**
     * Upsert document by source path (for sync operations).
     */
    @Transactional
    public KnowledgeDocument upsertBySourcePath(UUID projectId, String sourcePath, KnowledgeDocumentRequest request, UUID createdBy) {
        log.debug("Upserting knowledge document by source path: {} for project: {}", sourcePath, projectId);

        return repository.findByProjectIdAndSourcePath(projectId, sourcePath)
                .map(existing -> {
                    applyRequest(existing, request);
                    return repository.save(existing);
                })
                .orElseGet(() -> createProjectDocument(projectId, request, createdBy));
    }

    private void applyRequest(KnowledgeDocument doc, KnowledgeDocumentRequest request) {
        doc.setCategory(request.category());
        doc.setName(request.name());
        doc.setContent(request.content());
        doc.setPriority(request.priority());
        doc.setAppliesTo(request.appliesTo());
        doc.setSourcePath(request.sourcePath());
    }

    private void validateProjectExists(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project", projectId.toString());
        }
    }
}
