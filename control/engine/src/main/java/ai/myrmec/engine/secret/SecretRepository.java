package ai.myrmec.engine.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecretRepository extends JpaRepository<Secret, UUID> {

    /** Project-scoped secrets for a project (excluding globals). */
    List<Secret> findByProjectIdOrderByNameAsc(UUID projectId);

    /** Global secrets (project_id IS NULL). */
    @Query("SELECT s FROM Secret s WHERE s.project IS NULL ORDER BY s.name ASC")
    List<Secret> findAllGlobal();

    /**
     * Resolve a secret usable from the given project: either global or owned by
     * that project. Used by services that consume a secret reference (knowledge
     * repo, workflow tools, ...) to prevent cross-project leakage.
     */
    @Query("""
            SELECT s FROM Secret s
             WHERE s.id = :id
               AND (s.project IS NULL OR s.project.id = :projectId)
            """)
    Optional<Secret> findResolvable(@Param("id") UUID id, @Param("projectId") UUID projectId);

    Optional<Secret> findByProjectIdAndName(UUID projectId, String name);

    @Query("SELECT s FROM Secret s WHERE s.project IS NULL AND s.name = :name")
    Optional<Secret> findGlobalByName(@Param("name") String name);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    @Query("SELECT COUNT(s) > 0 FROM Secret s WHERE s.project IS NULL AND s.name = :name")
    boolean existsGlobalByName(@Param("name") String name);

    void deleteByProjectId(UUID projectId);
}
