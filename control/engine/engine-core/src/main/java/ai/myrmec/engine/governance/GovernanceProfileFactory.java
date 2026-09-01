// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.governance.dto.FeatureGroupResponse;
import ai.myrmec.engine.governance.dto.FeatureValueResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@link GovernanceProfileDefinition} into structured
 * {@link FeatureGroupResponse} rows for the compare matrix UI.
 *
 * <p>After the governance-enforcement rewrite, this reads
 * {@link GovernanceProfileDefinition#featureValues()} directly — no
 * legacy JSONB keys, no {@code extractValues} shim.
 */
@Slf4j
@Component
public class GovernanceProfileFactory {

    /**
     * Build the feature-group structure for a profile definition.
     *
     * @param definition the governance profile definition
     * @return list of feature groups, each containing its feature rows
     */
    public List<FeatureGroupResponse> buildGroups(GovernanceProfileDefinition definition) {
        Map<ProductFeature, Set<String>> values = definition.featureValues();
        Map<FeatureGroup, List<FeatureValueResponse>> grouped = new EnumMap<>(FeatureGroup.class);

        for (ProductFeature feature : ProductFeature.values()) {
            Set<String> currentValues = values.getOrDefault(feature, Set.of());
            FeatureValueResponse fvr = new FeatureValueResponse(
                    feature.name(),
                    feature.getDescription(),
                    feature.getSortOrder(),
                    feature.getPossibleValues(),
                    List.copyOf(currentValues));
            grouped.computeIfAbsent(feature.getGroup(), k -> new ArrayList<>()).add(fvr);
        }

        return Arrays.stream(FeatureGroup.values())
                .map(g -> new FeatureGroupResponse(
                        g.name(),
                        g.getDisplayName(),
                        g.getSortOrder(),
                        grouped.getOrDefault(g, List.of()).stream()
                                .sorted(Comparator.comparingInt(FeatureValueResponse::sortOrder))
                                .toList()))
                .sorted(Comparator.comparingInt(FeatureGroupResponse::sortOrder))
                .toList();
    }
}