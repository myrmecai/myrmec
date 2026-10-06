// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §22.8/§3.4 (Task 10): the terminal-linearization invalidation. When the
 * registry records an execution's FIRST terminal, every pending
 * interaction-family artifact settles CLOSED — nothing reopens and no
 * command is delivered to a dead execution:
 *
 * <ul>
 *   <li>PENDING dispatch commands (execution_command_outbox) EXPIRE — a
 *       late dispatcher poll never retransmits to a terminal execution;</li>
 *   <li>CONFIRMATION_REQUIRED / PENDING proposals (execution_control_requests)
 *       settle EXPIRED with errorCode EXECUTION_TERMINAL — the confirmation
 *       sweeper's window collapses at terminal;</li>
 *   <li>an unsettled pending interaction settles FAILED with the §22.6
 *       terminal code at its ORIGINAL response deadline semantics (the Task
 *       8 retention sweeper contract: never an extended post-terminal
 *       timeout; a later provider settlement updates accounting only).</li>
 * </ul>
 *
 * <p>All seams are idempotent and run under the CALLER's execution row lock
 * (the registry's terminal() holds it) — the REQUIRES_NEW-less join keeps
 * the invalidation atomic with the terminal record. Retained rows are the
 * audit trail; nothing is deleted.</p>
 */
@Service
@Slf4j
public class TerminalInvalidationService {

    private final SessionExecutionRepository executionRepository;
    private final ExecutionCommandOutboxRepository outboxRepository;
    private final ExecutionControlRequestRepository controlRequestRepository;
    private final ExecutionInteractionRepository interactionRepository;

    public TerminalInvalidationService(
            SessionExecutionRepository executionRepository,
            ExecutionCommandOutboxRepository outboxRepository,
            ExecutionControlRequestRepository controlRequestRepository,
            ExecutionInteractionRepository interactionRepository) {
        this.executionRepository = executionRepository;
        this.outboxRepository = outboxRepository;
        this.controlRequestRepository = controlRequestRepository;
        this.interactionRepository = interactionRepository;
    }

    /**
     * Invalidate the pending interaction-family artifacts of ONE execution
     * at its terminal linearization (idempotent: settled rows are no-ops).
     * Called with the execution row lock HELD (the registry's terminal
     * path); every write joins that transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void invalidatePending(UUID executionId, Instant now) {
        // 1. Pending dispatch commands EXPIRE (§3.4: an execution terminal
        // expires undispatched controls/chat).
        int expiredCommands = 0;
        for (ExecutionCommandOutbox row : outboxRepository
                .findByExecutionIdOrderBySequenceAsc(executionId)) {
            if (row.getStatus() == OutboxStatus.PENDING) {
                row.setStatus(OutboxStatus.EXPIRED);
                outboxRepository.save(row);
                expiredCommands += 1;
            }
        }

        // 2. CONFIRMATION_REQUIRED / PENDING proposals settle EXPIRED with
        // the terminal refusal code (§22.7; the sweeper window collapses).
        int expiredProposals = 0;
        for (ExecutionControlRequest row : controlRequestRepository
                .findByExecutionIdAndStatusIn(executionId,
                        List.of(InteractionControlStatus.PENDING,
                                InteractionControlStatus.CONFIRMATION_REQUIRED))) {
            row.setStatus(InteractionControlStatus.EXPIRED);
            row.setErrorCode("EXECUTION_TERMINAL");
            if (row.getDecidedAt() == null) {
                row.setDecidedAt(now);
            }
            controlRequestRepository.save(row);
            expiredProposals += 1;
        }

        // 3. The pending interaction settles FAILED with the §22.6 terminal
        // code (the Task 8 sweeper contract — usage status UNKNOWN unless a
        // known subtotal exists; the §3.5 public event is the sweeper's own
        // allocation seam's duty, this path only closes the row). The
        // uncertain-restart case (§22.6 HOST_STATE_LOST) never reaches this
        // seam: the reconcile service settles that row directly at
        // CANCEL_EXECUTION decision time, so every terminal here carries
        // EXECUTION_TERMINAL.
        SessionExecution execution = executionRepository.findById(executionId)
                .orElse(null);
        int settledInteractions = 0;
        if (execution != null && execution.getPendingInteractionId() != null) {
            ExecutionInteraction interaction = interactionRepository
                    .findById(execution.getPendingInteractionId()).orElse(null);
            if (interaction != null
                    && (interaction.getStatus() == InteractionStatus.ACCEPTED
                        || interaction.getStatus() == InteractionStatus.RUNNING)) {
                interaction.setStatus(InteractionStatus.FAILED);
                interaction.setError(terminalError("EXECUTION_TERMINAL",
                        "the execution reached its terminal before the "
                                + "interaction settled",
                        false));
                interaction.setCompletedAt(now);
                if (!"KNOWN".equals(interaction.getUsageStatus())) {
                    interaction.setUsageStatus("UNKNOWN");
                }
                interactionRepository.save(interaction);
                settledInteractions += 1;
            }
            // The pending pointer clears with the settlement (the Task 8
            // freeze contract: terminal clears the admission pointer).
            execution.setPendingInteractionId(null);
            executionRepository.save(execution);
        }

        if (expiredCommands + expiredProposals + settledInteractions > 0) {
            log.info("Terminal invalidation for execution {}: {} command(s) expired, "
                    + "{} proposal(s) expired, {} interaction(s) settled",
                    executionId, expiredCommands, expiredProposals, settledInteractions);
        }
    }

    /** The §3.2 error jsonb block (the §4 JSON shape; kept minimal). */
    private java.util.Map<String, Object> terminalError(
            String code, String message, boolean retryable) {
        return java.util.Map.of(
                "errorCode", code,
                "message", message,
                "retryable", retryable);
    }
}