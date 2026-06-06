package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    /**
     * Find agent by unique name.
     */
    Optional<Agent> findByName(String name);

    /**
     * Check if agent with name exists.
     */
    boolean existsByName(String name);

    /**
     * Find all agents with a specific status.
     */
    List<Agent> findByStatus(Agent.Status status);

    /**
     * Find all active agents.
     */
    @Query("SELECT a FROM Agent a WHERE a.status = 'ACTIVE'")
    List<Agent> findAllActive();

    /**
     * Find all agents for a given profile.
     */
    List<Agent> findByProfileId(UUID profileId);

    /**
     * Count agents for a given profile.
     */
    long countByProfileId(UUID profileId);

    /**
     * Find active agents for a profile.
     */
    @Query("SELECT a FROM Agent a WHERE a.profileId = :profileId AND a.status = 'ACTIVE'")
    List<Agent> findActiveByProfileId(@Param("profileId") UUID profileId);

    /**
     * Find agent by its registration key.
     */
    Optional<Agent> findByRegistrationKey(String registrationKey);

    /**
     * Find agents by project ID.
     */
    List<Agent> findByProjectId(UUID projectId);
}
