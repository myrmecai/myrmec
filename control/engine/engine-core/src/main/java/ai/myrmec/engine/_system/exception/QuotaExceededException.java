package ai.myrmec.engine._system.exception;

import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import lombok.Getter;

import java.util.UUID;

/**
 * Thrown when a pre-flight quota check blocks an operation.
 *
 * <p>Surfaced to clients as HTTP 429 by {@link GlobalExceptionHandler}.
 * Includes the scope that hit the ceiling and the configured limit so
 * the UI can render a meaningful banner without a follow-up call.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final QuotaScope scope;
    private final UUID scopeId;
    private final QuotaResourceType resourceType;
    private final long limitAmount;
    private final long consumedAmount;

    public QuotaExceededException(QuotaScope scope,
                                  UUID scopeId,
                                  QuotaResourceType resourceType,
                                  long limitAmount,
                                  long consumedAmount) {
        super(String.format(
                "Quota exceeded at scope %s for %s (limit=%d, consumed=%d).",
                scope, resourceType, limitAmount, consumedAmount));
        this.scope = scope;
        this.scopeId = scopeId;
        this.resourceType = resourceType;
        this.limitAmount = limitAmount;
        this.consumedAmount = consumedAmount;
    }
}
