// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import ai.myrmec.engine.quota.Quota;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8d &mdash; read-side projection of a {@link Quota}.
 */
public record QuotaResponse(
        UUID id,
        String scopeType,
        UUID scopeId,
        String resourceType,
        String period,
        long limitAmount,
        boolean enforced,
        String quotaType,
        String enforcementMode,
        String serviceType,
        Long maxExecutionAmount,
        Map<String, Object> tags,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    public static QuotaResponse from(Quota q) {
        return new QuotaResponse(
                q.getId(),
                q.getScopeType().name(),
                q.getScopeId(),
                q.getResourceType().name(),
                q.getPeriod().name(),
                q.getLimitAmount(),
                q.isEnforced(),
                q.getQuotaType().name(),
                q.getEnforcementMode().name(),
                q.getServiceType() != null ? q.getServiceType().name() : null,
                q.getMaxExecutionAmount(),
                q.getTags(),
                q.getCreatedBy(),
                q.getCreatedAt(),
                q.getUpdatedAt());
    }
}
