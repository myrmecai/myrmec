package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentHostRepository extends JpaRepository<AgentHost, UUID> {

    /**
     * Find agent by unique name.
     */
    Optional<AgentHost> findByName(String name);

    /**
     * Check if agent with name exists.
     */
    boolean existsByName(String name);

    /**
     * Find all agents with a specific status.
     */
    List<AgentHost> findByStatus(AgentHost.Status status);

    /**
     * Find all active agents.
     */
    @Query("SELECT a FROM AgentHost a WHERE a.status = 'ACTIVE'")
    List<AgentHost> findAllActive();

    /**
     * Find all agents for a given profile.
     */
    List<AgentHost> findByProfileId(UUID profileId);

    /**
     * Count agents for a given profile.
     */
    long countByProfileId(UUID profileId);

    /**
     * Find active agents for a profile.
     */
    @Query("SELECT a FROM AgentHost a WHERE a.profileId = :profileId AND a.status = 'ACTIVE'")
    List<AgentHost> findActiveByProfileId(@Param("profileId") UUID profileId);

    /**
     * Find agent by its registration key.
     */
    Optional<AgentHost> findByRegistrationKey(String registrationKey);

    /**
     * Find agents by project ID.
     */
    List<AgentHost> findByProjectId(UUID projectId);
}
