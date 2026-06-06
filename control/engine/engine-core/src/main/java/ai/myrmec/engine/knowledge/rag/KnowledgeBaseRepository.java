package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.retrieval.KnowledgeBaseScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    List<KnowledgeBase> findByScope(KnowledgeBaseScope scope);

    List<KnowledgeBase> findByProjectId(UUID projectId);

    List<KnowledgeBase> findByGroupId(UUID groupId);

    Optional<KnowledgeBase> findByScopeAndProjectIdAndName(KnowledgeBaseScope scope, UUID projectId, String name);

    Optional<KnowledgeBase> findByScopeAndGroupIdAndName(KnowledgeBaseScope scope, UUID groupId, String name);

    Optional<KnowledgeBase> findByScopeAndProjectIdIsNullAndGroupIdIsNullAndName(KnowledgeBaseScope scope, String name);
}
