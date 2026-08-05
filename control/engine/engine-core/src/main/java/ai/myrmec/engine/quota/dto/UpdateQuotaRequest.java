package ai.myrmec.engine.quota.dto;

import ai.myrmec.engine.quota.EnforcementMode;
import ai.myrmec.engine.quota.QuotaType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * Phase 8d &mdash; request body for {@code PUT /api/v1/admin/quotas/{id}}.
 *
 * <p>Only the mutable fields are exposed; scope, resource, and period
 * are immutable identity. To change them, delete and recreate.
 */
@Value
@Builder
@Jacksonized
public class UpdateQuotaRequest {

    @Min(0)
    long limitAmount;

    /** @deprecated use {@link #quotaType} and {@link #enforcementMode} */
    @Deprecated(forRemoval = false)
    Boolean enforced;

    String quotaType;

    String enforcementMode;

    @Min(0)
    Long maxExecutionAmount;

    Map<String, Object> tags;

    public QuotaType resolvedQuotaType() {
        return quotaType != null ? QuotaType.valueOf(quotaType) : QuotaType.CEILING;
    }

    public EnforcementMode resolvedEnforcementMode() {
        if (enforcementMode != null) {
            return EnforcementMode.valueOf(enforcementMode);
        }
        if (enforced != null) {
            return Boolean.TRUE.equals(enforced) ? EnforcementMode.BLOCK : EnforcementMode.TELEMETRY;
        }
        return EnforcementMode.BLOCK;
    }
}
