// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for engine-owned execution rows (§11.2). */
public interface SessionExecutionRepository extends JpaRepository<SessionExecution, UUID> {

    /** The one execution that may be in flight on a session (§11.3.4/5). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SessionExecution e WHERE e.sessionId = :sid AND e.state IN :states")
    List<SessionExecution> findWithLockBySessionIdAndStateIn(
            @Param("sid") UUID sessionId, @Param("states") List<SessionExecution.State> states);

    Optional<SessionExecution> findByIdAndTerminalMessageIdIsNull(UUID id);

    /** Per-session execution history newest-first (§8.1 conversation sequence assignment). */
    List<SessionExecution> findBySessionIdOrderBySequenceNoDesc(UUID sessionId);

    /** Pessimistic-lock lookup for exactly-once terminal transitions (§11.3.6). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SessionExecution e WHERE e.id = :id")
    Optional<SessionExecution> findWithLockById(@Param("id") UUID id);

    /** All executions ever started on a session (§11.3.5 orchestration exactly-one check). */
    List<SessionExecution> findBySessionId(UUID sessionId);

    Optional<SessionExecution> findBySessionIdAndSequenceNo(UUID sessionId, Integer sequenceNo);

    long countBySessionIdAndStateIn(UUID sessionId, List<SessionExecution.State> states);
}
