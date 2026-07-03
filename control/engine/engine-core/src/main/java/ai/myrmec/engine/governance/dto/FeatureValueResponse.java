// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance.dto;

import java.util.List;

/**
 * A single feature row in the compare matrix for a governance profile.
 *
 * @param code           the {@link ai.myrmec.engine.governance.ProductFeature} code
 * @param description    human-readable description
 * @param sortOrder      sort order within the group
 * @param possibleValues all valid values for this feature
 * @param currentValues  the values set for this profile — for single-value
 *                       features, a list of length 1; for multi-value,
 *                       the enabled subset
 */
public record FeatureValueResponse(
        String code,
        String description,
        int sortOrder,
        List<String> possibleValues,
        List<String> currentValues) {
}