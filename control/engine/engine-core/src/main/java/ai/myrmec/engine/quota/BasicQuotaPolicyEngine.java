// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaPolicyEngine;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
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
                Optional<Project> project = projectRepository.findById(startingScopeId);
                if (project.isPresent()) {
                    chain.add(new ScopeNode(Quota.Scope.PROJECT, project.get().getId()));
                    UUID groupId = project.get().getGroupId();
                    if (groupId != null) {
                        chain.add(new ScopeNode(Quota.Scope.GROUP, groupId));
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

    /** Pick the worst decision: blocked beats warning beats OK. */
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
        Instant periodStart = periodStart(q.getPeriod(), Instant.now());
        long used = consumptionRepository.findByQuotaIdAndPeriodStart(q.getId(), periodStart)
                .map(QuotaConsumption::getAmountUsed)
                .orElse(0L);
        long projected = used + amount;
        long limit = q.getLimitAmount();

        boolean blocked = q.getEnforcementMode() == EnforcementMode.BLOCK && projected > limit;
        boolean warning = !blocked && projected >= (long) (limit * WARNING_FRACTION);

        if (q.getEnforcementMode() == EnforcementMode.TELEMETRY) {
            blocked = false;
            warning = projected >= (long) (limit * WARNING_FRACTION);
        } else if (q.getEnforcementMode() == EnforcementMode.WARN && projected > limit) {
            blocked = false;
            warning = true;
        }

        if (q.getEnforcementMode() == EnforcementMode.BLOCK
                && projected >= (long) (limit * 1.2)
                && q.getPausedAt() == null) {
            log.warn("Quota {} hit 120% threshold, should be auto-paused", q.getId());
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
