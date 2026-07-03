// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance.dto;

import java.util.List;

/**
 * A group of features in the compare matrix.
 *
 * @param code        the {@link ai.myrmec.engine.governance.FeatureGroup} code
 * @param description human-readable group description
 * @param sortOrder   sort order for group display
 * @param features    feature rows within this group, sorted by sortOrder
 */
public record FeatureGroupResponse(
        String code,
        String description,
        int sortOrder,
        List<FeatureValueResponse> features) {
}