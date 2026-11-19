// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AuditChainHead} — per-scope chain state.
 *
 * <p>The {@link #findByScopeTypeAndTeamIdForUpdate} method acquires a
 * pessimistic write lock ({@code SELECT … FOR UPDATE}) to serialize
 * concurrent chain writes within one scope, preventing fork-on-insert.
 */
@Repository
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Long> {

    /**
     * Find the chain head for a scope (no lock). Used by verification and
     * non-locking reads.
     */
    Optional<AuditChainHead> findByScopeTypeAndTeamId(String scopeType, UUID teamId);

    /**
     * Find the chain head for a scope with a pessimistic write lock.
     * Used by the chain write path to prevent concurrent forks.
     *
     * <p>The lock is held until the transaction commits or rolls back.
     * Concurrent writers on the same scope will block here.
     *
     * <p>Note: JPQL doesn't support {@code IS :param} for NULL comparisons,
     * so we use two branches: one for ORG scope (teamId IS NULL) and
     * one for Team scope (teamId = :teamId).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM AuditChainHead h WHERE h.scopeType = :scopeType AND " +
            "(h.teamId IS NULL AND :teamId IS NULL OR h.teamId = :teamId)")
    Optional<AuditChainHead> findByScopeTypeAndTeamIdForUpdate(
            @Param("scopeType") String scopeType,
            @Param("teamId") UUID teamId);
}