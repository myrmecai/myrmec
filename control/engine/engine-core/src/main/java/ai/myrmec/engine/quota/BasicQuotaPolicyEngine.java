// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8b &mdash; Community implementation of {@link QuotaPolicyEngine}.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Walk the scope chain from the requested scope upward
 *       ({@code SERVICE &rarr; PROJECT &rarr; GROUP &rarr; ORG}).</li>
 *   <li>If the starting scope is {@code SERVICE}, evaluate its explicit
 *       {@code RESERVATION} first. If the reservation blocks, return
 *       immediately; otherwise continue to the project shared pool.</li>
 *   <li>For each parent scope, evaluate matching {@code CEILING} quotas and
 *       keep the most restrictive decision ("blocked beats warning beats OK").</li>
 *   <li>If no quota rows exist at all, return {@link QuotaDecision#unconstrained()}
 *       &mdash; Community policy is "no quota row, no limit".</li>
 * </ol>
 *
 * <p>Optimistic upsert &mdash; no row-lock. Trades small over-shoot
 * under high contention for higher throughput. Enterprise engine
 * tightens this with a SELECT FOR UPDATE.</p>
 *
 * <p>Warning band: 80% of limit. Kill switch (Phase 8c) lives in the
 * caller side rather than here so that "120% of limit" can be tuned
 * per-resource-type (a 20% over-shoot on tokens is fine, on USD-cost
 * it's a big deal).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasicQuotaPolicyEngine implements QuotaPolicyEngine {

    /** Warn level as a fraction of the limit. */
    public static final double WARNING_FRACTION = 0.80;

    private final QuotaRepository quotaRepository;
    private final QuotaConsumptionRepository consumptionRepository;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;
    private final WorkflowRepository workflowRepository;
    private final AssistantRepository assistantRepository;
    private final QuotaAutoPauseService autoPauseService;

    /** Toggle &mdash; off makes the engine return {@link QuotaDecision#unconstrained()} unconditionally. */
    @Value("${myrmec.quotas.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional(readOnly = true)
    public QuotaDecision check(QuotaScope scope, UUID scopeId, QuotaResourceType resource, long amount) {
        if (!enabled) {
            return QuotaDecision.unconstrained();
        }
        List<QuotaDecision> decisions = evaluateScopeChain(scope, scopeId, resource, amount);
        return decisions.isEmpty()
                ? QuotaDecision.unconstrained()
                : mostRestrictive(decisions);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConsumption(QuotaScope scope, UUID scopeId, QuotaResourceType resource, long amount) {
        if (!enabled || amount <= 0) {
            return;
        }
        List<ScopeNode> chain = buildScopeChain(scope, scopeId);
        for (ScopeNode node : chain) {
            List<Quota> quotas = findQuotasAtScope(node.scope(), node.scopeId(), resource);
            for (Quota q : quotas) {
                recordConsumptionForQuota(q, amount);
            }
        }
    }

    /** Evaluate every quota row from the requested scope upward. */
    private List<QuotaDecision> evaluateScopeChain(QuotaScope scope, UUID scopeId,
                                                   QuotaResourceType resource, long amount) {
        List<QuotaDecision> decisions = new ArrayList<>();
        List<ScopeNode> chain = buildScopeChain(scope, scopeId);

        for (ScopeNode node : chain) {
            List<Quota> quotas = findQuotasAtScope(node.scope(), node.scopeId(), resource);

            // SERVICE scope: evaluate any explicit reservation first.
            if (node.scope() == Quota.Scope.SERVICE && !quotas.isEmpty()) {
                quotas.stream()
                        .filter(q -> q.getQuotaType() == QuotaType.RESERVATION)
                        .min(Comparator.comparingLong(Quota::getLimitAmount))
                        .ifPresent(reservation -> {
                            QuotaDecision d = evaluate(reservation, amount);
                            decisions.add(d);
                        });
            }

            // Evaluate ceilings at this node.
            for (Quota q : quotas) {
                if (q.getPausedAt() != null) {
                    decisions.add(QuotaDecision.builder()
                            .blocked(true)
                            .warning(false)
                            .limitAmount(q.getLimitAmount())
                            .consumedAmount(0)
                            .remainingAmount(0)
                            .scopeHit(toSpiScope(q.getScopeType()))
                            .build());
                    continue;
                }
                if (q.getQuotaType() == QuotaType.CEILING) {
                    decisions.add(evaluate(q, amount));
                }
            }

            if (!quotas.isEmpty()) {
                // We found at least one quota row at this level; no need to
                // keep climbing once we've accounted for the nearest matching
                // scope. For SERVICE requests without a reservation we still
                // need the project ceiling, so we only break after PROJECT.
                if (node.scope() != Quota.Scope.SERVICE) {
                    break;
                }
            }
        }
        return decisions;
    }

    private List<Quota> findQuotasAtScope(Quota.Scope scope, UUID scopeId, QuotaResourceType resource) {
        Quota.ResourceType entityResource = toEntityResource(resource);
        if (scope == Quota.Scope.ORG) {
            return quotaRepository.findByScopeTypeAndResourceTypeAndPeriod(scope, entityResource, currentPeriod());
        }
        return quotaRepository.findByScopeTypeAndScopeIdAndResourceType(scope, scopeId, entityResource)
                .stream()
                .filter(q -> q.getPeriod() == currentPeriod())
                .toList();
    }

    /**
     * Period is implicit in the engine SPI. Community defaults to DAILY;
     * callers pass only the resource and amount they are about to spend now.
     */
    private Quota.Period currentPeriod() {
        return Quota.Period.DAILY;
    }

    private List<ScopeNode> buildScopeChain(QuotaScope startingScope, UUID startingScopeId) {
        List<ScopeNode> chain = new ArrayList<>();
        switch (startingScope) {
            case SERVICE -> {
                chain.add(new ScopeNode(Quota.Scope.SERVICE, startingScopeId));
                // For SERVICE scope, scopeId is the workflow / assistant
                // instance ID.  Resolve the project from the instance table.
                UUID projectId = resolveProjectFromServiceInstance(startingScopeId);
                if (projectId != null) {
                    chain.add(new ScopeNode(Quota.Scope.PROJECT, projectId));
                    Optional<Project> project = projectRepository.findById(projectId);
                    if (project.isPresent()) {
                        UUID groupId = project.get().getGroupId();
                        if (groupId != null) {
                            chain.add(new ScopeNode(Quota.Scope.GROUP, groupId));
                        }
                    }
                }
                // ORG rows have no specific scopeId in Community.
                chain.add(new ScopeNode(Quota.Scope.ORG, null));
            }
            case PROJECT -> {
                chain.add(new ScopeNode(Quota.Scope.PROJECT, startingScopeId));
                projectRepository.findById(startingScopeId).ifPresent(p -> {
                    if (p.getGroupId() != null) {
                        chain.add(new ScopeNode(Quota.Scope.GROUP, p.getGroupId()));
                    }
                });
                chain.add(new ScopeNode(Quota.Scope.ORG, null));
            }
            case GROUP -> {
                chain.add(new ScopeNode(Quota.Scope.GROUP, startingScopeId));
                chain.add(new ScopeNode(Quota.Scope.ORG, null));
            }
            case ORG -> chain.add(new ScopeNode(Quota.Scope.ORG, null));
        }
        return chain;
    }

    /**
     * Resolve the project ID from a service-instance UUID.
     * Tries workflow first, then assistant.  Returns {@code null} if
     * neither table contains the ID (e.g. legacy rows where scopeId
     * was the project ID itself — those will be caught by the project
     * lookup returning empty, and the chain will fall through to ORG).
     */
    private UUID resolveProjectFromServiceInstance(UUID serviceInstanceId) {
        Optional<Workflow> workflow = workflowRepository.findById(serviceInstanceId);
        if (workflow.isPresent()) {
            return workflow.get().getProject().getId();
        }
        Optional<Assistant> assistant = assistantRepository.findById(serviceInstanceId);
        if (assistant.isPresent()) {
            return assistant.get().getProjectId();
        }
        // Fallback: scopeId might be a project ID (legacy / test data).
        // This preserves backward compatibility with existing tests that
        // call check(SERVICE, projectId, ...) without creating a workflow.
        if (projectRepository.existsById(serviceInstanceId)) {
            return serviceInstanceId;
        }
        return null;
    }
    private QuotaDecision mostRestrictive(List<QuotaDecision> decisions) {
        QuotaDecision worst = null;
        for (QuotaDecision d : decisions) {
            if (worst == null) {
                worst = d;
            } else if (d.isBlocked() && !worst.isBlocked()) {
                worst = d;
            } else if (d.isWarning() && !worst.isBlocked() && !worst.isWarning()) {
                worst = d;
            }
        }
        return worst;
    }

    private void recordConsumptionForQuota(Quota q, long amount) {
        Instant periodStart = periodStart(q.getPeriod(), Instant.now());
        QuotaConsumption bucket = consumptionRepository
                .findByQuotaIdAndPeriodStart(q.getId(), periodStart)
                .orElseGet(() -> {
                    QuotaConsumption c = new QuotaConsumption();
                    c.setQuotaId(q.getId());
                    c.setPeriodStart(periodStart);
                    c.setAmountUsed(0);
                    return c;
                });
        bucket.setAmountUsed(bucket.getAmountUsed() + amount);
        try {
            consumptionRepository.save(bucket);
        } catch (Exception ex) {
            // Optimistic-upsert race: another thread won. Re-read +
            // increment once. If that still fails we log and move on
            // - quota over-shoot is preferable to aborting a real
            // request because of accounting noise.
            log.debug("Quota consumption save race on quota {} period {}: {}",
                    q.getId(), periodStart, ex.getMessage());
            consumptionRepository.findByQuotaIdAndPeriodStart(q.getId(), periodStart)
                    .ifPresent(existing -> {
                        existing.setAmountUsed(existing.getAmountUsed() + amount);
                        try {
                            consumptionRepository.save(existing);
                        } catch (Exception retry) {
                            log.warn("Quota consumption save retry failed for quota {} period {}: {}",
                                    q.getId(), periodStart, retry.getMessage());
                        }
                    });
        }
    }

    /** Internal: per-quota evaluation. */
    private QuotaDecision evaluate(Quota q, long amount) {
        long limit = q.getLimitAmount();

        // #40 — Per-execution cost ceiling: a single request may never exceed the
        // quota's per-run cap, regardless of how much pool budget remains. This is
        // the cheapest runaway-loop insurance — it fires on the requested amount
        // itself, before any pool arithmetic. Applies in BLOCK and WARN modes (a
        // runaway single run exceeds the cap in either); TELEMETRY never blocks.
        Long maxExecution = q.getMaxExecutionAmount();
        if (maxExecution != null && amount > maxExecution
                && q.getEnforcementMode() != EnforcementMode.TELEMETRY) {
            log.warn("Quota {} per-execution cap exceeded: requested {} > max {}",
                    q.getId(), amount, maxExecution);
            return QuotaDecision.builder()
                    .blocked(true)
                    .warning(false)
                    .limitAmount(limit)
                    .consumedAmount(0)
                    .remainingAmount(0)
                    .scopeHit(toSpiScope(q.getScopeType()))
                    .build();
        }

        Instant periodStart = periodStart(q.getPeriod(), Instant.now());
        long used = consumptionRepository.findByQuotaIdAndPeriodStart(q.getId(), periodStart)
                .map(QuotaConsumption::getAmountUsed)
                .orElse(0L);
        long projected = used + amount;

        boolean blocked = q.getEnforcementMode() == EnforcementMode.BLOCK && projected > limit;
        boolean warning = !blocked && projected >= (long) (limit * WARNING_FRACTION);

        if (q.getEnforcementMode() == EnforcementMode.TELEMETRY) {
            blocked = false;
            warning = projected >= (long) (limit * WARNING_FRACTION);
        } else if (q.getEnforcementMode() == EnforcementMode.WARN && projected > limit) {
            blocked = false;
            warning = true;
        }

        // #44 — 120% kill switch: a BLOCK quota whose consumption reaches 120%
        // of its limit is auto-paused (an admin must explicitly resume).
        // evaluate() runs in a read-only transaction, so the pause is delegated
        // to QuotaAutoPauseService (REQUIRES_NEW) — it commits even though this
        // evaluation tx is read-only. Overrun is still reported to the caller.
        if (q.getEnforcementMode() == EnforcementMode.BLOCK
                && projected >= (long) (limit * 1.2)
                && q.getPausedAt() == null) {
            try {
                autoPauseService.pauseIfNotAlready(q.getId());
            } catch (Exception ex) {
                // Never let a kill-switch failure break the quota evaluation.
                log.warn("Auto-pause failed for quota {}: {}", q.getId(), ex.getMessage());
            }
        }

        long remaining = Math.max(0, limit - used);
        return QuotaDecision.builder()
                .blocked(blocked)
                .warning(warning)
                .limitAmount(limit)
                .consumedAmount(used)
                .remainingAmount(remaining)
                .scopeHit(toSpiScope(q.getScopeType()))
                .build();
    }

    /** Bucket start for the given period at the supplied instant (UTC). */
    static Instant periodStart(Quota.Period period, Instant now) {
        return switch (period) {
            case DAILY -> LocalDate.ofInstant(now, ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            case MONTHLY_CALENDAR -> YearMonth.from(now.atZone(ZoneOffset.UTC))
                    .atDay(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            case LIFETIME -> Instant.EPOCH;
        };
    }

    private static Quota.Scope toEntityScope(QuotaScope spi) {
        return Quota.Scope.valueOf(spi.name());
    }

    private static QuotaScope toSpiScope(Quota.Scope entity) {
        return QuotaScope.valueOf(entity.name());
    }

    private static Quota.ResourceType toEntityResource(QuotaResourceType spi) {
        return Quota.ResourceType.valueOf(spi.name());
    }

    private record ScopeNode(Quota.Scope scope, UUID scopeId) {
    }
}
