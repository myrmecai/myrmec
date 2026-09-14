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
     * Find agent by its registration key.
     */
    Optional<AgentHost> findByRegistrationKey(String registrationKey);

    /**
     * Find agents by project ID.
     */
    List<AgentHost> findByProjectId(UUID projectId);

    /**
     * The single durable host of a given type owned by a user — the per-user
     * local host lookup (protocol §4.1 local registration; design 2026-09-11
     * §1.3 — local hosts are no longer project-scoped).
     */
    Optional<AgentHost> findByHostTypeAndOwnerUserId(AgentHostType hostType, UUID ownerUserId);
}
