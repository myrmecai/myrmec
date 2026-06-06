package ai.myrmec.engine.spi.quota;

import lombok.Builder;
import lombok.Value;

/**
 * Phase 8b &mdash; {@link QuotaPolicyEngine#check} result.
 *
 * <p>{@link #blocked} is the bottom line &mdash; when true, callers
 * MUST abort the request with a {@code QUOTA_EXCEEDED} signal.
 * {@link #warning} is informational &mdash; UI surfaces a banner;
 * callers proceed.</p>
 *
 * <p>{@link #limitAmount} / {@link #consumedAmount} / {@link
 * #remainingAmount} are populated even on a {@code blocked} response
 * so the caller can render a meaningful error message ("you are
 * 12,345 tokens over the 100,000 daily limit").</p>
 *
 * <p>{@link #scopeHit} identifies which level of the
 * org&rarr;group&rarr;project&rarr;user chain caused the decision.
 * Tells the UI which knob to tweak.</p>
 */
@Value
@Builder
public class QuotaDecision {
    boolean blocked;
    boolean warning;
    long limitAmount;
    long consumedAmount;
    long remainingAmount;
    QuotaScope scopeHit;

    /** Caller can proceed without any concern. */
    public static QuotaDecision unconstrained() {
        return QuotaDecision.builder()
                .blocked(false)
                .warning(false)
                .limitAmount(Long.MAX_VALUE)
                .consumedAmount(0)
                .remainingAmount(Long.MAX_VALUE)
                .build();
    }
}
