package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AgentInstanceRepository extends JpaRepository<AgentInstance, UUID> {

    /**
     * Find all instances for a given agent.
     */
    List<AgentInstance> findByAgentId(UUID agentId);

    /**
     * Find all instances with a specific status.
     */
    List<AgentInstance> findByStatus(AgentInstance.Status status);

    /**
     * Find all online instances for an agent.
     */
    List<AgentInstance> findByAgentIdAndStatus(UUID agentId, AgentInstance.Status status);

    /**
     * Find instances that haven't sent a heartbeat since the given time.
     */
    @Query("SELECT ai FROM AgentInstance ai WHERE ai.lastHeartbeatAt < :threshold AND ai.status = 'ONLINE'")
    List<AgentInstance> findStaleInstances(@Param("threshold") Instant threshold);

    /**
     * Count online instances for an agent.
     */
    @Query("SELECT COUNT(ai) FROM AgentInstance ai WHERE ai.agentId = :agentId AND ai.status = 'ONLINE'")
    long countOnlineByAgentId(@Param("agentId") UUID agentId);

    /**
     * Count all instances for an agent.
     */
    long countByAgentId(UUID agentId);
}
