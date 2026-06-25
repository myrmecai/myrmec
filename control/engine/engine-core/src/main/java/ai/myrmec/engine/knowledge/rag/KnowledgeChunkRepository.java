package ai.myrmec.engine.knowledge.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    List<KnowledgeChunk> findByKnowledgeSourceId(UUID knowledgeSourceId);

    /**
     * Chunks of a source in stable document order (insertion order, with the
     * primary key as a deterministic tiebreaker). Used by the #30 chunk-context
     * preview to locate a chunk's before/after neighbours.
     */
    List<KnowledgeChunk> findByKnowledgeSourceIdOrderByCreatedAtAscIdAsc(UUID knowledgeSourceId);

    Optional<KnowledgeChunk> findByKnowledgeSourceIdAndLocator(UUID knowledgeSourceId, String locator);

    long countByKnowledgeSourceId(UUID knowledgeSourceId);

    void deleteByKnowledgeSourceId(UUID knowledgeSourceId);
}
