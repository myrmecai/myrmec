// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.quota.BasicQuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.websocket.host.payload.ExecutionPolicyUpdatePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * §8.7 execution.policy.update producer (A4).
 *
 * <p>Trigger = the session's durably accounted usage advancing: the usage
 * recording seams ({@link ConversationEventIngestionService} TOKEN_USAGE
 * attribution and the terminal {@code usage} block) call
 * {@link #onUsageRecorded} with the execution's accounted totals. The service
 * resolves the session's current allowance from the quota engine's
 * most-restrictive remaining headroom and, when the accounted usage has
 * ADVANCED since the last frame sent for that execution, emits one
 * {@code execution.policy.update} with {executionId, dispatchId?, usage,
 * allowance}.</p>
 *
 * <p>Throttle (§8.7 practical V1): at most ONE update per execution per
 * accounting batch — an in-memory last-sent snapshot per executionId; a frame
 * is sent only when the accounted usage strictly advanced past it. Failed
 * sends clear the snapshot so the next accounting batch retries.</p>
 *
 * <p>Allowance source: {@link QuotaPolicyEngine#check} against the session's
 * project scope (the quota walk resolves the org→group→project chain);
 * {@code allowance.maxTokens} = the most restrictive remaining headroom, or
 * null when no quota row constrains the session (Community policy: no quota
 * row, no limit — the host's allowance stays unset). The engine never
 * loosens: only monotonic usage snapshots ride the frame, the host applies
 * the tighten-only rule.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPolicyService {

    private final ExecutionCommandSender executionCommandSender;
    private final SessionExecutionRepository executionRepository;
    private final ai.myrmec.engine.inference.SessionRepository sessionRepository;
    private final QuotaPolicyEngine quotaPolicyEngine;

    /** Toggle: off makes the producer a no-op (pre-A4 behaviour). */
    @Value("${myrmec.session.policy-updates.enabled:true}")
    private boolean enabled;

    /**
     * §8.7 throttle: last frame sent per executionId. At most one
     * policy.update per execution per accounting batch.
     */
    private final Map<UUID, SentSnapshot> lastSent = new ConcurrentHashMap<>();

    private record SentSnapshot(long functionCalls, long totalTokens, Instant at) {}

    /**
     * Usage-recording hook. Called whenever a session's accounted usage
     * advances (TOKEN_USAGE ingestion, terminal usage block). Compares the
     * durable totals against the last-sent snapshot and emits ONE
     * execution.policy.update when the usage advanced.
     *
     * @param executionId the engine-owned execution the usage belongs to
     * @param functionCalls the accounted orchestration function-call count
     * @param totalTokens the accounted total token consumption
     */
    public void onUsageRecorded(UUID executionId, long functionCalls, long totalTokens) {
        if (!enabled || executionId == null) {
            return;
        }
        SentSnapshot previous = lastSent.get(executionId);
        if (previous != null && functionCalls <= previous.functionCalls()
                && totalTokens <= previous.totalTokens()) {
            // No advance since the last frame — throttled (§8.7: one update
            // per accounting batch).
            return;
        }
        SessionExecution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            return;
        }
        // Only a live execution receives updates — terminal rows have no
        // host to enforce anything on.
        if (execution.getState() != SessionExecution.State.STARTING
                && execution.getState() != SessionExecution.State.RUNNING) {
            lastSent.remove(executionId);
            return;
        }
        Session session = sessionRepository.findById(execution.getSessionId()).orElse(null);
        if (session == null) {
            return;
        }
        ExecutionPolicyUpdatePayload.Allowance allowance = allowanceOf(session);
        ExecutionPolicyUpdatePayload payload = new ExecutionPolicyUpdatePayload(
                executionId,
                execution.getDispatchId(),
                new ExecutionPolicyUpdatePayload.Usage(functionCalls, totalTokens),
                allowance);
        boolean sent = executionCommandSender.policyUpdate(
                executionId, session, execution.getDispatchId(), payload);
        if (sent) {
            lastSent.put(executionId, new SentSnapshot(functionCalls, totalTokens, Instant.now()));
            log.info("execution.policy.update sent for execution {} (dispatch {}, calls {}, tokens {}, maxTokens {})",
                    executionId, execution.getDispatchId(), functionCalls, totalTokens,
                    allowance == null ? null : allowance.maxTokens());
        } else {
            // Keep (or clear) the snapshot so the next batch retries — a failed
            // send must not permanently suppress the enforcement channel.
            lastSent.remove(executionId);
            log.debug("execution.policy.update for execution {} not delivered (socket down) — will retry",
                    executionId);
        }
    }

    /** Forget one execution's throttle state (terminal housekeeping). */
    public void forget(UUID executionId) {
        if (executionId != null) {
            lastSent.remove(executionId);
        }
    }

    /**
     * The session's current token allowance: the most restrictive remaining
     * headroom across the project's quota chain (the same walk the quota
     * engine performs pre-flight). No constraining row → null (the host's
     * allowance stays unset — no host-side token limit).
     */
    private ExecutionPolicyUpdatePayload.Allowance allowanceOf(Session session) {
        try {
            QuotaDecision decision = quotaPolicyEngine.check(
                    QuotaScope.PROJECT, session.getProjectId(), QuotaResourceType.TOKENS, 1);
            if (decision.isBlocked() || decision.getLimitAmount() == Long.MAX_VALUE
                    || decision.getLimitAmount() <= 0) {
                return null;
            }
            // The tighten-only remaining headroom under the most restrictive
            // ceiling (§8.7: preserve or tighten — the accounted usage rides
            // the same frame so the host can reconcile).
            return new ExecutionPolicyUpdatePayload.Allowance(decision.getLimitAmount());
        } catch (Exception e) {
            // A quota-resolution failure must never break the usage channel.
            log.debug("Allowance resolution failed for session {}: {}", session.getId(), e.getMessage());
            return null;
        }
    }
}