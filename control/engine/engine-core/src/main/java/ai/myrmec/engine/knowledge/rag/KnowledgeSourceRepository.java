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

    /** Sources with a pending out-of-band re-sync request (#25a webhook). */
    List<KnowledgeSource> findByEnabledTrueAndSyncRequestedAtIsNotNull();

    Optional<KnowledgeSource> findByKnowledgeBaseIdAndName(UUID knowledgeBaseId, String name);
}
