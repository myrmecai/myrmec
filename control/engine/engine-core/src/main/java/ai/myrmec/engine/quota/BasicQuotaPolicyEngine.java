package ai.myrmec.engine.quota;

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
import java.util.List;
import java.util.UUID;

/**
 * Phase 8b &mdash; Community implementation of {@link QuotaPolicyEngine}.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Find quota rows matching (scope, scopeId, resource) for the
 *       given resource type.</li>
 *   <li>If none, return {@link QuotaDecision#unconstrained()} &mdash;
 *       Community policy is "no quota row, no limit".</li>
 *   <li>If multiple (e.g. one DAILY + one LIFETIME), evaluate each
 *       and return the most restrictive ("blocked beats warning beats
 *       OK").</li>
 *   <li>For each matched quota: compute the current period start,
 *       upsert the consumption bucket, compare amount_used + amount
 *       against limit_amount.</li>
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

    /** Toggle &mdash; off makes the engine return {@link QuotaDecision#unconstrained()} unconditionally. */
    @Value("${myrmec.quotas.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional(readOnly = true)
    public QuotaDecision check(QuotaScope scope, UUID scopeId, QuotaResourceType resource, long amount) {
        if (!enabled) {
            return QuotaDecision.unconstrained();
        }
        List<Quota> quotas = quotaRepository.findByScopeTypeAndScopeIdAndResourceType(
                toEntityScope(scope), scopeId, toEntityResource(resource));
        if (quotas.isEmpty()) {
            return QuotaDecision.unconstrained();
        }
        QuotaDecision worst = null;
        for (Quota q : quotas) {
            QuotaDecision d = evaluate(q, amount);
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

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConsumption(QuotaScope scope, UUID scopeId, QuotaResourceType resource, long amount) {
        if (!enabled || amount <= 0) {
            return;
        }
        List<Quota> quotas = quotaRepository.findByScopeTypeAndScopeIdAndResourceType(
                toEntityScope(scope), scopeId, toEntityResource(resource));
        for (Quota q : quotas) {
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
    }

    /** Internal: per-quota evaluation. */
    private QuotaDecision evaluate(Quota q, long amount) {
        Instant periodStart = periodStart(q.getPeriod(), Instant.now());
        long used = consumptionRepository.findByQuotaIdAndPeriodStart(q.getId(), periodStart)
                .map(QuotaConsumption::getAmountUsed)
                .orElse(0L);
        long projected = used + amount;
        long limit = q.getLimitAmount();
        boolean blocked = q.isEnforced() && projected > limit;
        boolean warning = !blocked && projected >= (long) (limit * WARNING_FRACTION);
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
}
