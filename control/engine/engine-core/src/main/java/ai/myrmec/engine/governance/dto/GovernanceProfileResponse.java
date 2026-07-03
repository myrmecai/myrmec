// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance.dto;

import ai.myrmec.engine.governance.GovernanceProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Governance Profile response — includes both the raw policies map
 * (for backward compatibility) and the structured feature groups
 * (for the compare matrix UI).
 *
 * @param code             profile code (STRICT, STANDARD, FLEXIBLE)
 * @param name             display name
 * @param description      short description
 * @param isBuiltIn        always true in V1
 * @param isSystem         true = cannot be deleted
 * @param isCurrentDefault true if this is the current org-level default
 * @param policies         raw policies JSONB (legacy)
 * @param groups           structured feature groups for the compare matrix
 * @param createdAt        creation timestamp
 * @param updatedAt        last update timestamp
 */
public record GovernanceProfileResponse(
        String code,
        String name,
        String description,
        Boolean isBuiltIn,
        Boolean isSystem,
        Boolean isCurrentDefault,
        Map<String, Object> policies,
        List<FeatureGroupResponse> groups,
        Instant createdAt,
        Instant updatedAt) {

    public static GovernanceProfileResponse from(GovernanceProfile p) {
        return from(p, false, null);
    }

    public static GovernanceProfileResponse from(GovernanceProfile p,
                                                  boolean isCurrentDefault,
                                                  List<FeatureGroupResponse> groups) {
        return new GovernanceProfileResponse(
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getIsBuiltIn(),
                p.getIsSystem(),
                isCurrentDefault,
                p.getPolicies(),
                groups,
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}