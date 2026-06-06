package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    /**
     * Find all organization-level knowledge documents.
     */
    List<KnowledgeDocument> findByScopeAndActiveOrderByPriorityDesc(KnowledgeScope scope, boolean active);

    /**
     * Find all project-level knowledge documents.
     */
    List<KnowledgeDocument> findByProjectIdAndActiveOrderByPriorityDesc(UUID projectId, boolean active);

    /**
     * Find all knowledge documents for a project (active or not).
     */
    List<KnowledgeDocument> findByProjectIdOrderByPriorityDesc(UUID projectId);

    /**
     * Find all organization-level documents (active or not).
     */
    List<KnowledgeDocument> findByScopeOrderByPriorityDesc(KnowledgeScope scope);

    /**
     * Find by scope and name (for uniqueness check).
     */
    Optional<KnowledgeDocument> findByScopeAndProjectIdAndName(KnowledgeScope scope, UUID projectId, String name);

    /**
     * Find by source path (for sync operations).
     */
    Optional<KnowledgeDocument> findByProjectIdAndSourcePath(UUID projectId, String sourcePath);

    /**
     * Find all documents for context resolution, ordered by priority.
     * Returns org-level + project-level documents.
     */
    @Query("""
        SELECT k FROM KnowledgeDocument k 
        WHERE k.active = true 
        AND (k.scope = 'ORGANIZATION' OR k.projectId = :projectId)
        ORDER BY k.priority DESC, k.scope ASC
        """)
    List<KnowledgeDocument> findForContextResolution(@Param("projectId") UUID projectId);

    /**
     * Check if name is unique within scope.
     */
    boolean existsByScopeAndProjectIdAndNameAndIdNot(KnowledgeScope scope, UUID projectId, String name, UUID excludeId);

    /**
     * Count documents by category for a project.
     */
    @Query("""
        SELECT k.category, COUNT(k) FROM KnowledgeDocument k 
        WHERE k.projectId = :projectId AND k.active = true 
        GROUP BY k.category
        """)
    List<Object[]> countByProjectIdGroupByCategory(@Param("projectId") UUID projectId);
}
