package ai.myrmec.engine.knowledge.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    List<KnowledgeChunk> findByKnowledgeSourceId(UUID knowledgeSourceId);

    Optional<KnowledgeChunk> findByKnowledgeSourceIdAndLocator(UUID knowledgeSourceId, String locator);

    long countByKnowledgeSourceId(UUID knowledgeSourceId);
}
