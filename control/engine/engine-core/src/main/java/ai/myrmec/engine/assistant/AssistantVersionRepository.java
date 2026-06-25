package ai.myrmec.engine.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssistantVersionRepository extends JpaRepository<AssistantVersion, UUID> {

    List<AssistantVersion> findByAssistantIdOrderByCreatedAtDesc(UUID assistantId);

    /** The single open Draft for an assistant, if one exists (single-Draft invariant). */
    Optional<AssistantVersion> findByAssistantIdAndStatus(UUID assistantId, AssistantVersion.Status status);

    List<AssistantVersion> findByAssistantIdAndStatusOrderByPublishedAtDesc(
            UUID assistantId, AssistantVersion.Status status);
}
