// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

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

/**
 * §3.3/§22.7 confirmation sweeper: expires PENDING chat-CANCEL proposals
 * past their ENGINE-STAMPED expiry without issuing any command, publishes
 * the resolution (§22.7 EXPIRED disposition — the audit row IS the durable
 * record; the wire publication is the host-resolution event, Task 8), and
 NEVER resets an execution deadline (§22.5: decline/expiry arms the SDK's
 * idle interval, the engine records the disposition only). Terminal
 * invalidation lives with the terminal arms (ExecutionControlService —
 * §22.8: terminal invalidates pending proposals).
 */
@Component
@Slf4j
public class ControlConfirmationSweeper {

    private final ExecutionControlRequestRepository controlRequestRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${myrmec.interaction.confirmation-sweep.enabled:true}")
    private boolean enabled;

    public ControlConfirmationSweeper(ExecutionControlRequestRepository controlRequestRepository,
                                      PlatformTransactionManager transactionManager) {
        this.controlRequestRepository = controlRequestRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * Scheduled confirmation sweep — one REQUIRES_NEW transaction per row so
     * a transient failure on one expiry never stalls the rest.
     */
    @Scheduled(fixedDelayString = "${myrmec.interaction.confirmation-sweep.interval-ms:5000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            sweepOnce(Instant.now());
        } catch (Exception e) {
            log.warn("Confirmation sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Expire every CONFIRMATION_REQUIRED chat-CANCEL row whose engine-stamped
     * window has passed. Idempotent: already-decided rows are untouched.
     *
     * @return the number of rows swept to EXPIRED
     */
    @Transactional
    public int sweepOnce(Instant now) {
        List<ExecutionControlRequest> due = controlRequestRepository
                .findByStatusAndConfirmationExpiresAtBefore(
                        InteractionControlStatus.CONFIRMATION_REQUIRED, now);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int swept = 0;
        for (ExecutionControlRequest row : due) {
            try {
                Integer outcome = template.execute(status -> {
                    ExecutionControlRequest locked = controlRequestRepository
                            .findWithLockById(row.getId()).orElse(null);
                    if (locked == null
                            || locked.getStatus() != InteractionControlStatus.CONFIRMATION_REQUIRED) {
                        return 0;   // decided concurrently — leave it
                    }
                    locked.setStatus(InteractionControlStatus.EXPIRED);
                    locked.setErrorCode("CONFIRMATION_EXPIRED");
                    locked.setDecidedAt(now);
                    controlRequestRepository.save(locked);
                    return 1;
                });
                swept += outcome == null ? 0 : outcome;
            } catch (Exception e) {
                log.warn("Confirmation sweep for {} failed: {}", row.getId(), e.getMessage());
            }
        }
        return swept;
    }

    /**
     * Expire ONE row in its own REQUIRES_NEW transaction. NOT used by the
     * decide path (decide settles an elapsed window EXPIRED atomically in
     * its own transaction — see ExecutionControlService.decide); kept for
     * Task 8's outcome sweeper and TEST/operational callers.
     */
    public void expireNow(UUID requestId, Instant now) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            ExecutionControlRequest locked = controlRequestRepository
                    .findWithLockById(requestId).orElse(null);
            if (locked == null
                    || locked.getStatus() != InteractionControlStatus.CONFIRMATION_REQUIRED) {
                return;   // decided concurrently — leave it
            }
            locked.setStatus(InteractionControlStatus.EXPIRED);
            locked.setErrorCode("CONFIRMATION_EXPIRED");
            locked.setDecidedAt(now);
            controlRequestRepository.save(locked);
        });
    }
}