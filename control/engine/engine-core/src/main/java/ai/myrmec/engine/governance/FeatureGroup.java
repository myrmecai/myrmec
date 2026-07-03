// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import lombok.Getter;

/**
 * Groups {@link ProductFeature} values for UI display (compare matrix).
 *
 * <p>Each feature belongs to exactly one group. The compare matrix
 * renders features grouped by this enum, sorted by {@link #sortOrder}.
 */
@Getter
public enum FeatureGroup {
    AI_CONTEXT("AI Context", 10, "AI context policies"),
    BUDGET("Budget", 20, "Token budget enforcement policies");

    private final String displayName;
    private final int sortOrder;
    private final String description;

    FeatureGroup(String displayName, int sortOrder, String description) {
        this.displayName = displayName;
        this.sortOrder = sortOrder;
        this.description = description;
    }
}