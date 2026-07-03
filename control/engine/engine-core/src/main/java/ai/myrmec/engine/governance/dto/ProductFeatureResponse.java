// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance.dto;

import ai.myrmec.engine.governance.FeatureGroup;
import ai.myrmec.engine.governance.ProductFeature;

import java.util.List;

/**
 * A product feature definition (used by the GET /product-features endpoint).
 *
 * @param code        the feature code
 * @param groupCode   the group code
 * @param groupName   the group display name
 * @param sortOrder   sort order within the group
 * @param description human-readable description
 * @param values      all valid values for this feature
 */
public record ProductFeatureResponse(
        String code,
        String groupCode,
        String groupName,
        int sortOrder,
        String description,
        List<String> values) {

    public static ProductFeatureResponse from(ProductFeature f) {
        FeatureGroup g = f.getGroup();
        return new ProductFeatureResponse(
                f.name(),
                g.name(),
                g.getDisplayName(),
                f.getSortOrder(),
                f.getDescription(),
                f.getPossibleValues());
    }
}