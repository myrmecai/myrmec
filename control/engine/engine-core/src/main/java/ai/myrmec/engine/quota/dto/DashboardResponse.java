// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/** Dashboard response: a tree of budgets for a single (resource, period). */
@Value
@Builder
@Jacksonized
public class DashboardResponse {
    List<BudgetTreeNode> tree;
}
