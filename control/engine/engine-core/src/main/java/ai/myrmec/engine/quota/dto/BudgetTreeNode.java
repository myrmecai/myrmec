// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/** Recursive read-model for the budget dashboard tree. */
@Value
@Builder
public class BudgetTreeNode {
    UUID id;
    String scopeType;
    UUID scopeId;
    String name;
    String resourceType;
    String period;
    Long effectiveLimit;
    Long consumed;
    String quotaType;
    String enforcementMode;
    boolean paused;
    List<BudgetTreeNode> children;
}
