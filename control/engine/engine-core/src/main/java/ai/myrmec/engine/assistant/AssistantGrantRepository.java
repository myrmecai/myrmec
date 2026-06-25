package ai.myrmec.engine.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssistantGrantRepository extends JpaRepository<AssistantGrant, UUID> {

    List<AssistantGrant> findByAssistantId(UUID assistantId);

    List<AssistantGrant> findByAssistantIdAndPrincipalTypeAndPrincipalId(
            UUID assistantId, AssistantGrant.PrincipalType principalType, String principalId);

    void deleteByAssistantId(UUID assistantId);
}
