package ai.myrmec.engine.quota.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 8d &mdash; request body for {@code POST /api/v1/admin/quotas}.
 *
 * <p>Validation is intentionally permissive on {@code tags} because
 * different tiers attach different metadata; the engine treats it as
 * opaque JSON. The hierarchy/ceiling check happens in
 * {@code QuotaService.validateChildTightensOnly} and surfaces as
 * 400 BAD_REQUEST through the global handler.
 */
@Value
@Builder
public class CreateQuotaRequest {

    @NotNull
    String scopeType;

    @NotNull
    UUID scopeId;

    @NotNull
    String resourceType;

    @NotNull
    String period;

    @Min(0)
    long limitAmount;

    boolean enforced;

    Map<String, Object> tags;
}
