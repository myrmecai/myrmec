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

    /** Team-scoped secrets for a Team (excluding globals). */
    List<Secret> findByTeamIdOrderByNameAsc(UUID teamId);

    /** Global secrets (team_id IS NULL). */
    @Query("SELECT s FROM Secret s WHERE s.team IS NULL ORDER BY s.name ASC")
    List<Secret> findAllGlobal();

    /**
     * Resolve a secret usable from the given team: either global or owned by
     * that team. Used by services that consume a secret reference (knowledge
     * repo, workflow tools, ...) to prevent cross-team leakage.
     */
    @Query("""
            SELECT s FROM Secret s
             WHERE s.id = :id
               AND (s.team IS NULL OR s.team.id = :teamId)
            """)
    Optional<Secret> findResolvable(@Param("id") UUID id, @Param("teamId") UUID teamId);

    Optional<Secret> findByTeamIdAndName(UUID teamId, String name);

    @Query("SELECT s FROM Secret s WHERE s.team IS NULL AND s.name = :name")
    Optional<Secret> findGlobalByName(@Param("name") String name);

    boolean existsByTeamIdAndName(UUID teamId, String name);

    @Query("SELECT COUNT(s) > 0 FROM Secret s WHERE s.team IS NULL AND s.name = :name")
    boolean existsGlobalByName(@Param("name") String name);

    void deleteByteamId(UUID teamId);
}
