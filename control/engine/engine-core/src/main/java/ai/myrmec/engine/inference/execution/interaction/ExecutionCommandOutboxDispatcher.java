// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * §3.4 (plan 2026-10-03-session-interaction): the persisted-command
 * dispatcher. Sends due PENDING outbox rows AFTER their owning transaction
 * committed, retransmitting the EXACT stored envelope — the row's
 * {@code id} IS the wire {@code messageId} and its {@code envelope} map IS
 * the frame; nothing is rebuilt and no messageId is reminted. A send
 * failure leaves the row PENDING (delivery_count advances) and a later
 * poll retransmits the same bytes. An ack (the protocol.ack arm, Task 8/10)
 * is the only PENDING → ACKED flip.
 *
 * <p>Retransmission pacing: the FIRST attempt fires at insert time
 * ({@code nextDeliveryAt = now} — set by the service at insert, unchanged
 * here). EVERY subsequent attempt re-stamps {@code nextDeliveryAt} with an
 * escalating backoff: 2^deliveryCount seconds, CAPPED AT 30s
 * (2s → 4s → 8s → 16s → 30s → 30s …). One row therefore takes ~1 minute
 * for its first five attempts instead of the earlier un-paced ~120
 * identical retransmits over the same 600s deadline window.
 */
@Component
@Slf4j
public class ExecutionCommandOutboxDispatcher {

    /** Pacing cap (seconds) shared with ExecutionControlService's javadoc. */
    static final int BACKOFF_CAP_SECONDS = 30;

    private final ExecutionCommandOutboxRepository outboxRepository;
    private final ExecutionCommandSender commandSender;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    @Value("${myrmec.interaction.dispatch-sweep.enabled:true}")
    private boolean enabled;

    public ExecutionCommandOutboxDispatcher(ExecutionCommandOutboxRepository outboxRepository,
                                            ExecutionCommandSender commandSender,
                                            ObjectMapper objectMapper,
                                            PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.commandSender = commandSender;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * §3.4 scheduled dispatch-poll sweep. Tests drive {@link #sweepOnce} /
     * {@link #dispatchDue} / {@link #sendNow} directly (the timer is off in
     * e2e).
     */
    @Scheduled(fixedDelayString = "${myrmec.interaction.dispatch-sweep.interval-ms:5000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            sweepOnce(Instant.now());
        } catch (Exception e) {
            log.warn("Outbox dispatch sweep failed: {}", e.getMessage());
        }
    }

    /** Dispatch every due PENDING row through the real sender. */
    public int sweepOnce(Instant now) {
        return dispatchDue(now);
    }

    /**
     * §3.4 dispatch of due PENDING rows: every row's send goes through
     * {@link ExecutionCommandSender#sendPersisted} which re-serializes the
     * STORED map and routes dedicated-channel-first. Each attempted row
     * advances delivery_count; a failed send leaves the row PENDING.
     *
     * @return the number of due PENDING rows attempted
     */
    @Transactional
    public int dispatchDue(Instant now) {
        List<ExecutionCommandOutbox> due = outboxRepository.findDuePending(now);
        for (ExecutionCommandOutbox entry : due) {
            attemptSend(entry);
            paceAndAdvance(entry);
        }
        return due.size();
    }

    /**
     * The after-commit arm: dispatch ONE row (executionId + messageId)
     * through the real sender. Absent/not-PENDING rows are a no-op 0.
     */
    public int sendPending(UUID executionId, UUID messageId) {
        TransactionTemplate template = new TransactionTemplate(
                txTemplate.getTransactionManager());
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer result = template.execute(status -> outboxRepository.findById(messageId)
                .filter(e -> e.getExecutionId().equals(executionId))
                .filter(e -> e.getStatus() == OutboxStatus.PENDING)
                .map(e -> {
                    attemptSend(e);
                    paceAndAdvance(e);
                    return 1;
                })
                .orElse(0));
        return result == null ? 0 : result;
    }

    /**
     * TEST seam: dispatch ONE row through a caller-provided transport that
     * receives the EXACT re-serialization of the stored envelope map (the
     * same bytes the real sender would hand to the relay). Production code
     * uses {@link #sendPending} / {@link #dispatchDue}.
     *
     * @return 1 when a due PENDING row was ATTEMPTED, 0 otherwise (no row,
     *         not PENDING, or the transport failed)
     */
    public int sendNow(UUID messageId, Function<String, Boolean> transport) {
        TransactionTemplate template = new TransactionTemplate(
                txTemplate.getTransactionManager());
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer result = template.execute(status -> outboxRepository.findById(messageId)
                .filter(e -> e.getStatus() == OutboxStatus.PENDING)
                .map(e -> {
                    boolean sent = attemptSendWith(e, transport);
                    paceAndAdvance(e);
                    return sent ? 1 : 0;
                })
                .orElse(0));
        return result == null ? 0 : result;
    }

    // ------------------------------------------------------------------

    /** The real send: sender re-serializes the STORED map (dedicated-first). */
    private boolean attemptSend(ExecutionCommandOutbox entry) {
        try {
            return commandSender.sendPersisted(entry.getSessionId(),
                    entry.getHostInstanceId(), entry.getEnvelope());
        } catch (Exception e) {
            log.warn("Outbox {} dispatch failed: {}", entry.getId(), e.getMessage());
            return false;
        }
    }

    /** Test seam variant: the transport receives the exact stored-map bytes. */
    private boolean attemptSendWith(ExecutionCommandOutbox entry,
                                    Function<String, Boolean> transport) {
        try {
            return Boolean.TRUE.equals(transport.apply(
                    objectMapper.writeValueAsString(entry.getEnvelope())));
        } catch (Exception e) {
            log.warn("Outbox {} send failed: {}", entry.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Advance the attempt bookkeeping after EVERY attempt (§3.4): the
     * delivery count increments, and {@code nextDeliveryAt} is re-stamped
     * with the escalating 2^deliveryCount-second backoff capped at 30s —
     * success AND failure alike (a SUCCESSFUL send also stays PENDING until
     * the ack arrives, so its retransmit window must be paced too, not just
     * the failures'). The row is persisted under the caller's transaction.
     */
    private void paceAndAdvance(ExecutionCommandOutbox entry) {
        int count = (entry.getDeliveryCount() == null ? 0 : entry.getDeliveryCount()) + 1;
        entry.setDeliveryCount(count);
        long backoff = (long) Math.min(1L << Math.min(count, 30), BACKOFF_CAP_SECONDS);
        entry.setNextDeliveryAt(Instant.now().plusSeconds(backoff));
        outboxRepository.save(entry);
    }
}