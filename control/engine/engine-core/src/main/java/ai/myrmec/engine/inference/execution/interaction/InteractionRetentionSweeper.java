// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.ResourceInUseDetail;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 8 retention sweeper (§3.5 + §22.8): the two expiry duties the
 * outcome arms leave behind.
 *
 * <p><b>Outcome sweep</b> — an admitted interaction without an outcome
 * settles only until its ORIGINAL responseDeadline (§22.8: "permit
 * outcome settlement only until that interaction's original
 * responseDeadline"; terminal does not restart that deadline, NOT an
 * extended post-terminal timeout). Past that instant the sweeper records
 * an explicit interaction failure — EXECUTION_TERMINAL when the execution
 * already went terminal, INTERACTION_TIMEOUT when the execution is still
 * live (the response window elapsed with no outcome) — with an explicit
 * usage status (UNKNOWN unless known parts were reported), clears the
 * pending pointer, and allocates the §3.5 public stream event under the
 * execution row lock THROUGH THE VIEW SERVICE'S SINGLE allocate KERNEL
 * (review Fix 3b: one allocation kernel + one after-commit fan-out — the
 * duplicate local write was deleted and swept events now also publish
 * the §3.5 wakeup hint + peer relay). The sweep does NOT route through
 * the interaction service: the §22.8 settlement window has lapsed, so no
 * usage accounting may ride a swept failure. It never reopens terminal
 * state and never issues controls.</p>
 *
 * <p><b>Transcript redaction</b> — settled interactions' transcript text
 * (requestText/answerText) redacts to CONTENT_EXPIRED markers after the
 * session policy's transcriptRetentionDays while identity/usage/audit
 * metadata is retained (§3.5: "Retention deletion redacts transcript text
 * … replay returns CONTENT_EXPIRED markers"). Late settlements update
 * ACCOUNTING only; they can never replace a settled/swept answer.</p>
 */
@Component
@Slf4j
public class InteractionRetentionSweeper {

    /** §3.5: the retention marker (package-visible for the stream tests). */
    public static final String CONTENT_EXPIRED = "[CONTENT_EXPIRED]";
    private static final List<InteractionStatus> UNSETTLED =
            List.of(InteractionStatus.ACCEPTED, InteractionStatus.RUNNING);

    private final ExecutionInteractionRepository interactionRepository;
    private final SessionExecutionRepository executionRepository;
    private final ExecutionViewService viewService;
    private final PlatformTransactionManager transactionManager;

    @Value("${myrmec.interaction.retention-sweep.enabled:true}")
    private boolean enabled;

    public InteractionRetentionSweeper(
            ExecutionInteractionRepository interactionRepository,
            SessionExecutionRepository executionRepository,
            ExecutionViewService viewService,
            PlatformTransactionManager transactionManager) {
        this.interactionRepository = interactionRepository;
        this.executionRepository = executionRepository;
        this.viewService = viewService;
        this.transactionManager = transactionManager;
    }

    /**
     * Scheduled retention sweep. Tests drive {@link #sweepOnce} /
     * {@link #redactExpired} directly (the timer is off in e2e). The
     * redaction pass runs through a TransactionTemplate — calling the
     * {@code @Transactional} method directly would bypass the proxy
     * (self-invocation; the same pattern ControlConfirmationSweeper uses).
     */
    @Scheduled(fixedDelayString = "${myrmec.interaction.retention-sweep.interval-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            sweepOnce(Instant.now());
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.executeWithoutResult(status -> redactExpired(Instant.now()));
        } catch (Exception e) {
            log.warn("Interaction retention sweep failed: {}", e.getMessage());
        }
    }

    /**
     * The unsettled-outcome sweep: ACCEPTED/RUNNING rows past their
     * ORIGINAL responseDeadline. One REQUIRES_NEW transaction per row so a
     * transient failure never stalls the rest.
     *
     * @return the number of rows swept to a settled failure
     */
    public int sweepOnce(Instant now) {
        List<ExecutionInteraction> due = interactionRepository
                .findByStatusInAndResponseDeadlineBefore(UNSETTLED, now);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int swept = 0;
        for (ExecutionInteraction row : due) {
            try {
                Integer outcome = template.execute(status ->
                        sweepOne(row.getId(), now) ? 1 : 0);
                swept += outcome == null ? 0 : outcome;
            } catch (Exception e) {
                log.warn("Outcome sweep for interaction {} failed: {}",
                        row.getId(), e.getMessage());
            }
        }
        return swept;
    }

    /**
     * The §3.5 retention redaction: settled interactions completed before
     * (now − transcriptRetentionDays) have their transcript text redacted
     * to CONTENT_EXPIRED markers. Unsettled rows are never text-redacted
     * BEFORE their outcome/expiry handling (§3.5: "Pending records are not
     * deleted before outcome/expiry handling").
     *
     * @return the number of rows redacted
     */
    @Transactional
    public int redactExpired(Instant now) {
        List<ExecutionInteraction> settled = interactionRepository
                .findByStatusIn(List.of(InteractionStatus.COMPLETED,
                        InteractionStatus.FAILED));
        int redacted = 0;
        for (ExecutionInteraction row : settled) {
            SessionExecution execution = executionRepository.findById(row.getExecutionId())
                    .orElse(null);
            if (execution == null) {
                continue;
            }
            long retentionDays = retentionDaysOf(execution);
            Instant cutoff = now.minus(Duration.ofDays(retentionDays));
            Instant settledAt = row.getCompletedAt() != null ? row.getCompletedAt()
                    : row.getResponseDeadline();
            if (settledAt == null || settledAt.isAfter(cutoff)) {
                continue;
            }
            if (row.getRequestText() == null && row.getAnswerText() == null) {
                continue;   // already redacted
            }
            row.setRequestText(redactedText(row.getRequestText()));
            row.setAnswerText(redactedText(row.getAnswerText()));
            interactionRepository.save(row);
            redacted++;
        }
        return redacted;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** One row's expiry settlement (inside the caller's transaction); the
     * re-locked read guards against a concurrent outcome settling first. */
    private boolean sweepOne(UUID interactionId, Instant now) {
        ExecutionInteraction locked = interactionRepository.findWithLockById(interactionId)
                .orElse(null);
        if (locked == null || !UNSETTLED.contains(locked.getStatus())) {
            return false;   // settled concurrently — the outcome wins
        }
        SessionExecution execution = executionRepository
                .findWithLockById(locked.getExecutionId()).orElse(null);
        if (execution == null) {
            return false;
        }
        boolean terminal = execution.getTerminalMessageId() != null
                || switch (execution.getState()) {
            case COMPLETED, FAILED, PAUSED, CANCELLED, REJECTED -> true;
            default -> false;
        };
        String errorCode = terminal ? "EXECUTION_TERMINAL" : "INTERACTION_TIMEOUT";
        String message = terminal
                ? "the execution went terminal before the outcome settled (§22.8 settlement window)"
                : "the response deadline elapsed without an outcome";

        locked.setStatus(InteractionStatus.FAILED);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", errorCode);
        error.put("message", message);
        error.put("retryable", false);
        locked.setError(error);
        // The usage status is already explicit on the row (UNKNOWN for a
        // never-outcome interaction; KNOWN stays when partial usage rode
        // an earlier frame); it is never zero-massaged here.
        if (locked.getUsageStatus() == null) {
            locked.setUsageStatus("UNKNOWN");
        }
        locked.setTerminalMessageId("sweep-" + interactionId);
        locked.setCompletedAt(now);
        interactionRepository.save(locked);

        // Clear the pending pointer when THIS interaction owned it.
        if (interactionId.equals(execution.getPendingInteractionId())) {
            execution.setPendingInteractionId(null);
        }
        // §3.5: the swept failure allocates its PUBLIC stream event under
        // the execution row lock THROUGH THE VIEW SERVICE'S SINGLE
        // allocate kernel (IDs/status — never transcript text), so swept
        // events carry the same after-commit wakeup + peer relay the
        // outcome arms get (one allocation kernel; no duplicate writer).
        viewService.allocate(execution, "execution.interaction.failed",
                ExecutionViewService.sweptFailureData(locked, errorCode));
        return true;
    }

    /** The session policy's transcript retention (§3.5: default 30 days,
     * tightened by session/project policy; the execution snapshot is the
     * effective immutable source). */
    private long retentionDaysOf(SessionExecution execution) {
        Map<String, Object> policy = execution.getInteractionPolicy();
        if (policy != null && policy.get("transcriptRetentionDays") instanceof Number n) {
            return Math.max(1, n.longValue());
        }
        return InteractionProperties.DEFAULT_TRANSCRIPT_RETENTION_DAYS;
    }

    /** The CONTENT_EXPIRED marker preserving the byte-length bound. */
    private static String redactedText(String text) {
        if (text == null) {
            return null;
        }
        return CONTENT_EXPIRED;
    }
}
