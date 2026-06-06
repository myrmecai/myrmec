package ai.myrmec.engine.spi.quota;

/**
 * Phase 8b &mdash; SPI seam for quota enforcement.
 *
 * <p>Community ships {@code BasicQuotaPolicyEngine} which gives every
 * caller the standard "hard block at limit, warn at 80%, kill at
 * 120% of cumulative" behaviour. Enterprise tiers can replace this
 * bean to add classification-aware policies, time-of-day rules,
 * elastic burst credits, etc.</p>
 *
 * <p>Implementations MUST be safe under contention &mdash; the
 * decision and the consumption record may straddle a few milliseconds
 * across a cluster, so callers expect minor over-shoots (one or two
 * units past the limit on a tight race). The Enterprise quota engine
 * tightens this with a database row lock; Community uses optimistic
 * upsert which trades a small race for higher throughput.</p>
 */
public interface QuotaPolicyEngine {

    /**
     * Pre-flight check &mdash; tells the caller whether to proceed and
     * how much headroom remains.
     *
     * @param scope    {@code ORG} / {@code GROUP} / {@code PROJECT} / {@code USER}
     * @param scopeId  the entity id of that scope
     * @param resource {@code TOKENS} / {@code COST_USD_CENTS}
     * @param amount   the expected amount the caller is about to spend
     *                 (a best-effort estimate is fine &mdash; the final
     *                 cost is reconciled via {@link #recordConsumption}).
     * @return policy decision; never null.
     */
    QuotaDecision check(QuotaScope scope, java.util.UUID scopeId, QuotaResourceType resource, long amount);

    /**
     * Post-flight bookkeeping &mdash; records that the caller actually
     * spent {@code amount} units. Always called even after a {@link
     * QuotaDecision#blocked} pre-check if the caller chooses to ignore
     * the block (e.g. system actions in dry-run mode), so the
     * consumption table stays a complete record.
     */
    void recordConsumption(QuotaScope scope, java.util.UUID scopeId, QuotaResourceType resource, long amount);
}
