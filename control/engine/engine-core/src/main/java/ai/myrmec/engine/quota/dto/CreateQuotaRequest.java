// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import ai.myrmec.engine.quota.EnforcementMode;
import ai.myrmec.engine.quota.QuotaType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 8d &mdash; request body for {@code POST /api/v1/admin/quotas}.
 *
 * <p>Validation is intentionally permissive on {@code tags} because
 * different tiers attach different metadata; the engine treats it as
 * opaque JSON. The hierarchy/ceiling check happens in
 * {@code QuotaService.validateChildTightensOnly} and surfaces as
 * 400 BAD_REQUEST through the global handler.</p>
 *
 * <p>{@code scopeId} is nullable to support ORG-scope quotas (which
 * have no entity to reference). For SERVICE scope, {@code scopeId} is
 * the workflow / assistant instance UUID and {@code serviceType}
 * distinguishes which table to look up. The service layer resolves
 * the {@code projectId} from the instance.</p>
 */
@Value
@Builder
@Jacksonized
public class CreateQuotaRequest {

    @NotNull
    String scopeType;

    /** Null for ORG scope; group/project/instance UUID otherwise. */
    UUID scopeId;

    @NotNull
    String resourceType;

    @NotNull
    String period;

    @Min(0)
    long limitAmount;

    /** @deprecated use {@link #quotaType} and {@link #enforcementMode} */
    @Deprecated(forRemoval = false)
    Boolean enforced;

    String quotaType;

    String enforcementMode;

    String serviceType;

    @Min(0)
    Long maxExecutionAmount;

    Map<String, Object> tags;

    /** Convenience accessor for callers that don't supply a quota type yet. */
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