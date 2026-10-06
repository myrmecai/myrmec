// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §3.4 persisted command dispatch. Insert happens in the SAME transaction
 * as admission/intent/proposal resolution (Task 6/8 wiring); the
 * dispatcher polls due PENDING rows, retransmitting the EXACT stored
 * envelope/messageId; ACK records durable SDK handling.
 */
public interface ExecutionCommandOutboxRepository extends JpaRepository<ExecutionCommandOutbox, UUID> {

    /** §3.4 dispatch-poll sweep input: due PENDING records, sequence order. */
    @Query("""
            SELECT o FROM ExecutionCommandOutbox o
            WHERE o.status = ai.myrmec.engine.inference.execution.interaction.OutboxStatus.PENDING
              AND o.nextDeliveryAt <= :now
              AND o.expiresAt > :now
            ORDER BY o.sequence ASC
            """)
    List<ExecutionCommandOutbox> findDuePending(@Param("now") Instant now);

    /**
     * §3.4: record the durable ACK by messageId (the row id). Idempotent:
     * an already-ACKED row stays ACKED and keeps its first acknowledgedAt.
     */
    @Modifying
    @Query("""
            UPDATE ExecutionCommandOutbox o
            SET o.status = ai.myrmec.engine.inference.execution.interaction.OutboxStatus.ACKED,
                o.acknowledgedAt = COALESCE(o.acknowledgedAt, :at)
            WHERE o.id = :messageId
              AND o.status = ai.myrmec.engine.inference.execution.interaction.OutboxStatus.PENDING
            """)
    int ackByMessageId(@Param("messageId") UUID messageId, @Param("at") Instant at);

    /**
     * §3.4: an execution terminal expires undispatched PENDING commands.
     * Superseded/ACKED rows remain auditable.
     */
    @Modifying
    @Query("""
            UPDATE ExecutionCommandOutbox o
            SET o.status = ai.myrmec.engine.inference.execution.interaction.OutboxStatus.EXPIRED
            WHERE o.executionId = :executionId
              AND o.status = ai.myrmec.engine.inference.execution.interaction.OutboxStatus.PENDING
            """)
    int expirePendingForExecution(@Param("executionId") UUID executionId);

    /** Per-execution outbox history (dispatch order). */
    List<ExecutionCommandOutbox> findByExecutionIdOrderBySequenceAsc(UUID executionId);
}