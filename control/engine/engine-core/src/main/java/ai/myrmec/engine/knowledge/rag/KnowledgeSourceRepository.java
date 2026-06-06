package ai.myrmec.engine.knowledge.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, UUID> {

    List<KnowledgeSource> findByKnowledgeBaseId(UUID knowledgeBaseId);

    List<KnowledgeSource> findByEnabledTrueAndSyncScheduleIsNotNull();

    Optional<KnowledgeSource> findByKnowledgeBaseIdAndName(UUID knowledgeBaseId, String name);
}
