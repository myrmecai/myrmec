package ai.myrmec.engine.quota.dto;

import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Phase 8d &mdash; request body for {@code PUT /api/v1/admin/quotas/{id}}.
 *
 * <p>Only the mutable fields are exposed; scope, resource, and period
 * are immutable identity. To change them, delete and recreate.
 */
@Value
@Builder
public class UpdateQuotaRequest {

    @Min(0)
    long limitAmount;

    boolean enforced;

    Map<String, Object> tags;
}
