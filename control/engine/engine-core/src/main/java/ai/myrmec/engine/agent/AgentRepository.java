package ai.myrmec.engine.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    /**
     * Find all instances for a given agent.
     */
    List<Agent> findByAgentHostId(UUID agentId);

    /**
     * Find all instances with a specific status.
     */
    List<Agent> findByStatus(Agent.Status status);

    /**
     * Find all online instances for an agent.
     */
    List<Agent> findByAgentHostIdAndStatus(UUID agentId, Agent.Status status);

    /**
     * Find the worker bound to a specific conversation (at most one).
     */
    Optional<Agent> findByConversationIdAndStatus(UUID conversationId, Agent.Status status);

    /**
     * Find instances that haven't sent a heartbeat since the given time.
     */
    @Query("SELECT ai FROM Agent ai WHERE ai.lastHeartbeatAt < :threshold AND ai.status = 'IDLE'")
    List<Agent> findStaleInstances(@Param("threshold") Instant threshold);

    /**
     * Count online instances for an agent.
     */
    @Query("SELECT COUNT(ai) FROM Agent ai WHERE ai.agentHostId = :agentHostId AND ai.status = 'IDLE'")
    long countOnlineByAgentHostId(@Param("agentHostId") UUID agentHostId);

    /**
     * Count connected (non-{@code DEAD}) instances for a host — i.e. workers
     * whose control socket is live regardless of whether they're currently
     * idle or busy. Drives the #88 conversation agent-availability indicator.
     */
    @Query("SELECT COUNT(ai) FROM Agent ai WHERE ai.agentHostId = :agentHostId AND ai.status <> 'DEAD'")
    long countConnectedByAgentHostId(@Param("agentHostId") UUID agentHostId);

    /**
     * Count all instances for an agent.
     */
    long countByAgentHostId(UUID agentId);

    /**
     * Count workers currently holding an active binding to a conversation
     * (any of {@code statuses}). Used by the #87 backlog drainer to skip a
     * conversation whose turn is already in flight, preventing a double
     * dispatch when a second worker comes online mid-turn.
     */
    long countByConversationIdAndStatusIn(UUID conversationId, java.util.Collection<Agent.Status> statuses);

    /**
     * Atomically claim a warm worker for a conversation: flip exactly the
     * row identified by {@code id} from {@code IDLE} to {@code RESERVED},
     * pinning the conversation and profile version at reserve-time
     * (agent-concurrency §9.5). The {@code status = IDLE} guard makes this a
     * compare-and-set: concurrent dispatchers racing for the same warm
     * worker see at most one win (return {@code 1}); the loser gets
     * {@code 0} and tries the next candidate. No {@code RETURNING} is used
     * so the query runs identically on H2 and PostgreSQL.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Agent a SET a.status = :reserved, a.conversationId = :conversationId, "
            + "a.profileVersionId = :profileVersionId, a.stateChangedAt = :now "
            + "WHERE a.id = :id AND a.status = :idle")
    int reserveIfIdle(@Param("id") UUID id,
                      @Param("idle") Agent.Status idle,
                      @Param("reserved") Agent.Status reserved,
                      @Param("conversationId") UUID conversationId,
                      @Param("profileVersionId") UUID profileVersionId,
                      @Param("now") Instant now);

    /**
     * Find workers stuck in a transient binding state ({@code RESERVED} or
     * {@code CONNECTING}) whose last FSM transition predates {@code cutoff}.
     * The reserve/connect-timeout reapers reclaim these to {@code IDLE}
     * (agent-concurrency §9.5).
     */
    @Query("SELECT a FROM Agent a WHERE a.status = :status AND a.stateChangedAt < :cutoff")
    List<Agent> findByStatusAndStateChangedAtBefore(@Param("status") Agent.Status status,
                                                    @Param("cutoff") Instant cutoff);

    /**
     * Find live-but-unresponsive workers: any worker whose status is in
     * {@code statuses} (the mid-lifecycle set) yet has not heartbeat since
     * {@code cutoff}. The HOST_LOST reaper flips these to {@code DEAD}
     * (agent-concurrency §9.5).
     */
    @Query("SELECT a FROM Agent a WHERE a.lastHeartbeatAt < :cutoff AND a.status IN :statuses")
    List<Agent> findStaleByStatusIn(@Param("cutoff") Instant cutoff,
                                    @Param("statuses") List<Agent.Status> statuses);

    /**
     * Find workers homed on a lost replica: any worker whose status is in
     * {@code statuses} ({@code BOUND}/{@code CONNECTING}) and whose
     * {@code home_node_id} is one of the {@code DOWN} nodes. The
     * {@code HomeNodeFailoverService} sweep re-homes the ones whose agent
     * host is still alive (agent-concurrency §9.11).
     */
    @Query("SELECT a FROM Agent a WHERE a.status IN :statuses AND a.homeNodeId IN :homeNodeIds")
    List<Agent> findByStatusInAndHomeNodeIdIn(@Param("statuses") List<Agent.Status> statuses,
                                              @Param("homeNodeIds") List<String> homeNodeIds);

    /**
     * Atomically transition a worker from {@code RESERVED} to
     * {@code CONNECTING} (agent-concurrency §9.5). The
     * {@code status = RESERVED} guard makes this a compare-and-set: if the
     * worker has already advanced to {@code BOUND} (because
     * {@code attachConversation} won the race), the update affects zero rows
     * and the caller treats the late bind.ack as a harmless no-op.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Agent a SET a.status = :connecting, a.stateChangedAt = :now "
            + "WHERE a.id = :id AND a.status = :reserved")
    int connectIfReserved(@Param("id") UUID id,
                          @Param("reserved") Agent.Status reserved,
                          @Param("connecting") Agent.Status connecting,
                          @Param("now") Instant now);
}
