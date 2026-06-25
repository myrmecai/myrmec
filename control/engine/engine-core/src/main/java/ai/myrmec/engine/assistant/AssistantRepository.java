package ai.myrmec.engine.assistant;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssistantRepository extends JpaRepository<Assistant, UUID> {

    List<Assistant> findByProjectIdOrderByNameAsc(UUID projectId);

    /** Case-insensitive name-uniqueness lookup within a project (excludes archived). */
    Optional<Assistant> findByProjectIdAndNameIgnoreCaseAndArchivedAtIsNull(UUID projectId, String name);

    /**
     * Pessimistic row lock on the parent — taken by publish before flipping
     * {@code current_version_id} and by session-start before pinning, so readers
     * never see a torn state (assistant-entity.md §5.6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assistant a where a.id = :id")
    Optional<Assistant> findByIdForUpdate(@Param("id") UUID id);
}
