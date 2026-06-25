package ai.myrmec.engine.node;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the engine replica registry (slice 4a).
 */
public interface EngineNodeRepository extends JpaRepository<EngineNode, String> {

    /**
     * Non-DOWN replicas whose last heartbeat is older than {@code cutoff} —
     * candidates the reaper marks {@code DOWN}. The owning replica is
     * excluded by the caller (a node never reaps itself).
     */
    @Query("""
            SELECT n FROM EngineNode n
            WHERE n.status <> ai.myrmec.engine.node.EngineNode.Status.DOWN
              AND n.lastHeartbeatAt < :cutoff
              AND n.nodeId <> :selfNodeId
            """)
    List<EngineNode> findStaleNodes(@Param("cutoff") Instant cutoff,
                                    @Param("selfNodeId") String selfNodeId);

    /**
     * Refreshes this replica's heartbeat in its own statement so the timer
     * never collides with the JPA entity's managed state.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE EngineNode n
            SET n.lastHeartbeatAt = :now
            WHERE n.nodeId = :nodeId
            """)
    int touchHeartbeat(@Param("nodeId") String nodeId, @Param("now") Instant now);

    /**
     * Ids of every replica currently marked {@code DOWN}. The
     * {@code HomeNodeFailoverService} sweep uses these to find workers homed
     * on a lost replica that must be re-homed (agent-concurrency §9.11).
     */
    @Query("""
            SELECT n.nodeId FROM EngineNode n
            WHERE n.status = ai.myrmec.engine.node.EngineNode.Status.DOWN
            """)
    List<String> findDownNodeIds();
}
