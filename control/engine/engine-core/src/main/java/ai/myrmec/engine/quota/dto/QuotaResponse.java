package ai.myrmec.engine.quota.dto;

import ai.myrmec.engine.quota.Quota;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8d &mdash; read-side projection of a {@link Quota}.
 */
@Value
@Builder
public class QuotaResponse {

    UUID id;
    String scopeType;
    UUID scopeId;
    String resourceType;
    String period;
    long limitAmount;
    boolean enforced;
    Map<String, Object> tags;
    UUID createdBy;
    Instant createdAt;
    Instant updatedAt;

    public static QuotaResponse from(Quota q) {
        return QuotaResponse.builder()
                .id(q.getId())
                .scopeType(q.getScopeType().name())
                .scopeId(q.getScopeId())
                .resourceType(q.getResourceType().name())
                .period(q.getPeriod().name())
                .limitAmount(q.getLimitAmount())
                .enforced(q.isEnforced())
                .tags(q.getTags())
                .createdBy(q.getCreatedBy())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .build();
    }
}
