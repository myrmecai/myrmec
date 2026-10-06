// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * §3.2 interaction-record persistence. The pending pointer lives on the
 * execution row (session_executions.pending_interaction_id) and is set/
 * cleared under its pessimistic-write lock — this repository owns the
 * record side of that contract.
 */
public interface ExecutionInteractionRepository extends JpaRepository<ExecutionInteraction, UUID> {

    /** Serial transcript page (§5 GET /interactions) — ordinal order. */
    List<ExecutionInteraction> findByExecutionIdOrderByOrdinalAsc(UUID executionId);

    /** Status filter for outcome sweeps / pending checks (§3.5). */
    List<ExecutionInteraction> findByExecutionIdAndStatus(UUID executionId, InteractionStatus status);

    /** §4 idempotency: an identical retry resolves the original row. */
    Optional<ExecutionInteraction> findByExecutionIdAndActorUserIdAndClientRequestId(
            UUID executionId, UUID actorUserId, UUID clientRequestId);

    /** §3.2 ordinal slot lookup (allocated under the execution row lock). */
    Optional<ExecutionInteraction> findByExecutionIdAndOrdinal(UUID executionId, Long ordinal);

    /** §3.2/§22.6 terminal dedup by the outcome frame's messageId. */
    Optional<ExecutionInteraction> findByTerminalMessageId(String terminalMessageId);

    /** Row lock for outcome settlement (§3.5: outcome/expiry handling). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ExecutionInteraction i WHERE i.id = :id")
    Optional<ExecutionInteraction> findWithLockById(@Param("id") UUID id);

    /** Outcome-sweep input (§3.5): accepted/running rows past their deadline. */
    List<ExecutionInteraction> findByStatusInAndResponseDeadlineBefore(
            List<InteractionStatus> statuses, java.time.Instant cutoff);

    /** §3.5 retention input: all settled rows of one status family. */
    List<ExecutionInteraction> findByStatusIn(List<InteractionStatus> statuses);
}