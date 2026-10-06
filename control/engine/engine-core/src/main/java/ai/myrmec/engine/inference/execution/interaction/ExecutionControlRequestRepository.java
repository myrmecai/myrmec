// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * §3.3 control intent + proposal persistence. Wire proposal ID is the row
 * PK; dispositions are §22.7 plus the PENDING confirmation window.
 */
public interface ExecutionControlRequestRepository extends JpaRepository<ExecutionControlRequest, UUID> {

    /** Per-execution transcript of control intents (audit + sweeps). */
    List<ExecutionControlRequest> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    /** §3.3 idempotency key member (execution, actor, clientRequestId). */
    Optional<ExecutionControlRequest> findByExecutionIdAndActorUserIdAndClientRequestId(
            UUID executionId, UUID actorUserId, UUID clientRequestId);

    /** §22.7: SDK proposals carry their wire proposal ID as the row PK. */
    Optional<ExecutionControlRequest> findByExecutionIdAndInteractionIdAndOrigin(
            UUID executionId, UUID interactionId, String origin);

    /** §3.3 confirmation-sweeper input: pending confirmations past expiry. */
    List<ExecutionControlRequest> findByStatusAndConfirmationExpiresAtBefore(
            InteractionControlStatus status, Instant cutoff);

    /** §3.3 terminal invalidation input: actionable rows for an execution. */
    List<ExecutionControlRequest> findByExecutionIdAndStatusIn(
            UUID executionId, List<InteractionControlStatus> statuses);

    /** §22.7: a resolution revision bump serializes under the row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ExecutionControlRequest r WHERE r.id = :id")
    Optional<ExecutionControlRequest> findWithLockById(@Param("id") UUID id);
}