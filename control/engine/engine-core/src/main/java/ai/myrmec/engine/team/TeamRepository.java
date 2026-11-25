package ai.myrmec.engine.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Team entities.
 */
@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    /**
     * Find all teams by status.
     */
    List<Team> findByStatus(TeamStatus status);

    /**
     * Check if a Team with the given name exists.
     */
    boolean existsByName(String name);

    Optional<Team> findByName(String name);

    /**
     * Find all teams belonging to the given group.
     */
    List<Team> findByGroupId(UUID groupId);

    /**
     * Count teams in a given group (used to block group deletion).
     */
    long countByGroupId(UUID groupId);
}
