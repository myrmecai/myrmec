// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Session} rows.
 */
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * All sessions for a ref id (conversation id or workflow request id).
     * Historically this was a single-result Optional, but crashed reconnects
     * can leave several rows for one conversation; a single-result query then
     * fails with IncorrectResultSizeDataAccessException and - the caller being
     * inside a joined transaction - silently poisons it, so the outer commit
     * dies with UnexpectedRollbackException (archive 500). Callers iterate.
     */
    List<Session> findByRefIdAndServiceType(UUID refId, String serviceType);

    List<Session> findByRefId(UUID refId);

    Optional<Session> findByIdAndStatus(UUID id, String status);

    /** §7.1 offer-expiry sweep input: pending reservations past their lease. */
    List<Session> findByAllocationStateAndOfferExpiresAtBefore(String allocationState, Instant cutoff);

    /**
     * §7.1/§12.2 sweep input: pending offers AND accepted-but-unopened
     * sessions past their lease (the same column re-armed as the
     * initialization deadline at accept).
     */
    List<Session> findByAllocationStateInAndOfferExpiresAtBefore(Collection<String> allocationStates,
                                                                 Instant cutoff);

    /** §12.2 idle-lease sweep input: ACTIVE conversations past expiry. */
    List<Session> findByAllocationStateAndIdleLeaseExpiresAtBefore(String allocationState, Instant cutoff);

    /** §11.1 state-guarded lookup for the dispatch path. */
    Optional<Session> findByRefIdAndServiceTypeAndAllocationStateIn(
            UUID refId, String serviceType, Collection<String> allocationStates);

    /** §7.1 atomic allocation: capacity counted from session FSM rows. */
    @Query("SELECT COUNT(s) FROM Session s WHERE s.hostInstanceId = :id AND s.allocationState IN :states")
    long countByHostInstanceIdAndAllocationStateIn(@Param("id") UUID id,
                                                   @Param("states") Collection<String> states);

    /** §7.1 atomic allocation state transitions lock the session row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findWithLockById(@Param("id") UUID id);
}