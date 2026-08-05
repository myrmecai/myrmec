// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.UUID;

/** Service-level budgets under a project, plus the unreserved shared pool. */
@Value
@Builder
@Jacksonized
public class ProjectServiceBudgetsResponse {
    UUID projectId;
    String resourceType;
    String period;
    SharedPool sharedPool;
    List<EffectiveQuota> serviceBudgets;
}
