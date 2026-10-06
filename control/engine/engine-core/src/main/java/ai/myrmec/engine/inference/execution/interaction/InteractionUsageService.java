// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.inference.execution.SessionPolicyService;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 8 accounting (plan §3.5/§22.8): the idempotent usage settlement
 * into quota.
 *
 * <p>The plan-verbatim accounting rule block:</p>
 *
 * <pre>
 * new quota charge = max(0, authoritative cumulative attempt usage - already accounted)
 * interaction subtotal is attribution, not an additional charge
 * UNKNOWN never overwrites known totals with zero
 * </pre>
 *
 * <p>Settlement identities: every durable usage event carries a unique
 * {@code settlementId} + {@code source} (ORCHESTRATION / INTERACTION).
 * Dedup keys on the settlementId ALONE (a settlementId settles AT MOST
 * ONCE — the settled set lives on the execution's
 * {@code interaction_usage} jsonb projection, §3.1 — no new table, 035 is
 * the fixed schema; settlementId derivations embed the source so distinct
 * sources never collide). The authoritative number is the
 * CUMULATIVE attempt total the frame carries; the quota charge is the
 * unaccounted DELTA (cumulative − accounted, floored at 0), so the
 * aggregate terminal usage can never double bill the interaction
 * subtotals it already includes, and a late provider settlement only
 * tops up whatever the engine had not yet accounted.</p>
 */
@Service
@Slf4j
public class InteractionUsageService {

    /** §3.5: the settlement's source attribution. */
    public enum Source { ORCHESTRATION, INTERACTION }

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;
    private final SessionPolicyService sessionPolicyService;

    public InteractionUsageService(SessionExecutionRepository executionRepository,
                                   SessionRepository sessionRepository,
                                   QuotaPolicyEngine quotaPolicyEngine,
                                   SessionPolicyService sessionPolicyService) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.quotaPolicyEngine = quotaPolicyEngine;
        this.sessionPolicyService = sessionPolicyService;
    }

    /**
     * Settle ONE durable usage event into the attempt's accounting.
     *
     * @param executionId  the engine-owned execution
     * @param settlementId the unique settlement identity (idempotency key)
     * @param source       ORCHESTRATION (aggregate terminal frames) or
     *                     INTERACTION (chat outcomes / late provider usage)
     * @param interactionId the attributed interaction (null for aggregates)
     * @param usage        the usage block (token counts); null/empty = unknown
     * @param usageStatus  KNOWN or UNKNOWN
     */
    @Transactional
    public void settleUsage(UUID executionId, String settlementId, Source source,
                            UUID interactionId, Map<String, Object> usage,
                            String usageStatus) {
        if (executionId == null || settlementId == null || settlementId.isBlank()) {
            return;
        }
        SessionExecution locked = executionRepository.findWithLockById(executionId)
                .orElse(null);
        if (locked == null) {
            log.debug("Usage settlement {} for unknown execution {} — dropped",
                    settlementId, executionId);
            return;
        }

        Map<String, Object> accounting = accountingOf(locked);

        // Idempotency: the SAME settlementId never settles twice (the
        // aggregate terminal replays, durable USAGE_UPDATED redelivery…).
        if (settledAlready(accounting, settlementId)) {
            log.debug("Usage settlement {} already accounted — skipping", settlementId);
            return;
        }

        boolean known = "KNOWN".equalsIgnoreCase(usageStatus)
                && usage != null && !usage.isEmpty();
        long cumulative = known ? totalTokensOf(usage) : -1L;

        long accounted = longOf(accounting.get("accountedTokens"));
        long charge;
        if (known) {
            // The accounting rule (plan-verbatim): charge the unaccounted
            // delta only. UNKNOWN never overwrites known totals with zero.
            charge = Math.max(0, cumulative - accounted);
        } else {
            // UNKNOWN: nothing to charge, the accounted total stands.
            charge = 0;
        }

        if (charge > 0) {
            chargeQuota(locked, charge);
        }

        // Persist the projection BEFORE the next settlement sees it (the
        // row lock serializes concurrent settlements).
        accounting.put("accountedTokens", Math.max(accounted, known ? cumulative : accounted));
        if (known && usageStatus != null) {
            accounting.put("usageStatus", "KNOWN");
        } else if (accounting.get("usageStatus") == null) {
            accounting.put("usageStatus", usageStatus == null ? "UNKNOWN" : usageStatus);
        }
        markSettled(accounting, settlementId);
        if (interactionId != null && charge >= 0 && known) {
            attributedTokens(accounting).put(interactionId.toString(),
                    longOf(attributedTokens(accounting).get(interactionId.toString()))
                            + totalTokensOf(usage));
        }
        locked.setInteractionUsage(accounting);
        executionRepository.save(locked);

        // §8.7: the accounted totals advance — notify the tighten-only
        // policy producer (throttled internally; failures never break
        // settlement).
        notifyProducer(locked, accounting);
        log.info("Usage settlement {} settled for execution {} (source {}, charge {}) — "
                        + "accounted {} tokens",
                settlementId, executionId, source, charge,
                accounting.get("accountedTokens"));
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The quota charge: the PROJECT scope of the execution's session (the
     * same walk the pre-flight check resolves). Failures never break the
     * settlement — the accounting projection still advanced (the next
     * batch reconciles).
     */
    private void chargeQuota(SessionExecution locked, long charge) {
        try {
            UUID projectId = sessionRepository.findById(locked.getSessionId())
                    .map(Session::getProjectId)
                    .orElse(null);
            if (projectId == null) {
                log.warn("Usage settlement could not resolve the project (session {}) — "
                        + "charge skipped", locked.getSessionId());
                return;
            }
            quotaPolicyEngine.recordConsumption(QuotaScope.PROJECT, projectId,
                    QuotaResourceType.TOKENS, charge);
        } catch (Exception e) {
            log.warn("Quota charge failed (accounting advanced anyway): {}", e.getMessage());
        }
    }

    private void notifyProducer(SessionExecution locked, Map<String, Object> accounting) {
        try {
            long tokens = longOf(accounting.get("accountedTokens"));
            long calls = longOf(accounting.get("functionCalls"));
            sessionPolicyService.onUsageRecorded(locked.getId(), calls, tokens);
        } catch (Exception e) {
            log.debug("Policy-producer notification failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> accountingOf(SessionExecution locked) {
        Map<String, Object> accounting = locked.getInteractionUsage();
        if (accounting == null) {
            accounting = new LinkedHashMap<>();
        }
        if (accounting.get("accountedTokens") == null) {
            accounting.put("accountedTokens", 0L);
        }
        if (accounting.get("usageStatus") == null) {
            accounting.put("usageStatus", "UNKNOWN");
        }
        if (!(accounting.get("settled") instanceof Map)) {
            accounting.put("settled", new LinkedHashMap<String, Long>());
        }
        if (!(accounting.get("attributedTokens") instanceof Map)) {
            accounting.put("attributedTokens", new LinkedHashMap<String, Long>());
        }
        return accounting;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> attributedTokens(Map<String, Object> accounting) {
        return (Map<String, Long>) accounting.computeIfAbsent("attributedTokens",
                k -> new LinkedHashMap<String, Long>());
    }

    private static boolean settledAlready(Map<String, Object> accounting, String settlementId) {
        return accounting.get("settled") instanceof Map<?, ?> settled
                && settled.containsKey(settlementId);
    }

    @SuppressWarnings("unchecked")
    private static void markSettled(Map<String, Object> accounting, String settlementId) {
        ((Map<String, Long>) accounting.get("settled")).put(settlementId, System.nanoTime());
    }

    private static long totalTokensOf(Map<String, Object> usage) {
        // The authoritative cumulative number: the frame's totalTokens when
        // present, else the known input+output sum.
        if (usage.get("totalTokens") instanceof Number n) {
            return Math.max(0, n.longValue());
        }
        long input = numberOrZero(usage.get("inputTokens"));
        long output = numberOrZero(usage.get("outputTokens"));
        return input + output;
    }

    private static long numberOrZero(Object value) {
        return value instanceof Number n ? Math.max(0, n.longValue()) : 0L;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
