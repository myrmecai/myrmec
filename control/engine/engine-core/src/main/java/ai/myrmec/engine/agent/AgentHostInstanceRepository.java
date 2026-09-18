// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for append-only {@link AgentHostInstance} rows (protocol §3).
 */
@Repository
public interface AgentHostInstanceRepository extends JpaRepository<AgentHostInstance, UUID> {

    List<AgentHostInstance> findByAgentHostIdAndStatus(UUID agentHostId, AgentHostInstance.Status status);

    Optional<AgentHostInstance> findByAgentHostIdAndOwnerUserIdAndStatus(
            UUID agentHostId, UUID ownerUserId, AgentHostInstance.Status status);

    /** §6.1 replay-idempotency lookup: the live run with this nonce, if any. */
    Optional<AgentHostInstance> findByAgentHostIdAndInstanceNonceAndStatus(
            UUID agentHostId, String instanceNonce, AgentHostInstance.Status status);

    long countByAgentHostIdAndStatus(UUID agentHostId, AgentHostInstance.Status status);

    /** Instance history — newest run first (restart timeline, design §3.2a). */
    List<AgentHostInstance> findByAgentHostIdOrderByOpenedAtDesc(UUID agentHostId);

    /** §9 (P6-T6): every OPEN instance across hosts — the host-lost sweep's liveness set. */
    List<AgentHostInstance> findByStatus(AgentHostInstance.Status status);

    /**
     * §13 (A2): RECOVERING instances whose bounded recovery window lapsed —
     * the retention sweep's close-fallback input.
     */
    List<AgentHostInstance> findByStatusAndRecoveryExpiresAtBefore(
            AgentHostInstance.Status status, Instant before);

    /**
     * §12.2 heartbeat-staleness sweep input: OPEN instances whose last
     * liveness signal predates the staleness cutoff.
     */
    List<AgentHostInstance> findByStatusAndLastHeartbeatAtBefore(
            AgentHostInstance.Status status, Instant before);

    /** §12.2 sweep: OPEN instances that never sent a heartbeat (openedAt grace). */
    List<AgentHostInstance> findByStatusAndLastHeartbeatAtIsNull(AgentHostInstance.Status status);

    /** §7.1 atomic reservation: allocation locks the instance row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM AgentHostInstance i WHERE i.id = :id")
    Optional<AgentHostInstance> findWithLockById(@Param("id") UUID id);

    /**
     * Close every still-OPEN instance homed on a control node (engine
     * replica shutdown / failover). Bulk-safe: write-once semantics are
     * preserved because only OPEN rows are touched.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AgentHostInstance i SET i.status = 'CLOSED', i.liveKey = null, "
            + "i.closedAt = :now, i.closeReason = :reason "
            + "WHERE i.controlNodeId = :controlNodeId AND i.status = 'OPEN'")
    int closeByControlNodeId(@Param("controlNodeId") String controlNodeId,
                             @Param("now") Instant now,
                             @Param("reason") String reason);

    /**
     * Retention sweep input (design §3.2a): delete CLOSED rows older than the
     * cutoff. Never matches OPEN rows — live instances are always retained.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AgentHostInstance i WHERE i.status = 'CLOSED' AND i.closedAt < :cutoff")
    int deleteClosedBefore(@Param("cutoff") Instant cutoff);
}
