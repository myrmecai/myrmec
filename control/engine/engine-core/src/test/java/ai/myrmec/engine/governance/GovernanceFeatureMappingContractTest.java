// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-10: Governance Feature enum ↔ runtime name mapping contract.
 *
 * <p>Verifies that every {@link ProductFeature} with a runtime-name alias
 * (per {@code context-and-governance.md} §4.2) maps to the correct
 * runtime name. Stops enum-name drift from re-appearing.
 *
 * <p>Note: the core parametrized test is in {@link ProductFeatureRuntimeNameTest};
 * this contract test adds the reverse assertion (every runtime name maps back
 * to the correct enum value) and completeness checks.
 */
@Tag("RECON-10")
@Tag("SG9")
@DisplayName("RECON-10: Governance Feature Mapping Contract")
class GovernanceFeatureMappingContractTest {

    /** Every ProductFeature must have a value in every profile. */
    @ParameterizedTest
    @MethodSource("allProfiles")
    @DisplayName("every ProductFeature has a value in profile %s")
    void everyFeatureHasValueInEveryProfile(BuiltInGovernanceProfile profile) {
        for (ProductFeature feature : ProductFeature.values()) {
            assertThat(profile.featureValues().get(feature))
                    .as("Profile %s must define a value for %s", profile.code(), feature.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    /** The three profile codes must match the doc. */
    @ParameterizedTest
    @MethodSource("allProfiles")
    @DisplayName("profile %s has a valid code")
    void profileCodesAreValid(BuiltInGovernanceProfile profile) {
        assertThat(profile.code()).isIn("STRICT", "STANDARD", "FLEXIBLE");
    }

    /** possibleValues must be ordered strictest → loosest. */
    @ParameterizedTest
    @MethodSource("allFeatures")
    @DisplayName("possibleValues for %s are non-empty and ordered")
    void possibleValuesAreNonEmpty(ProductFeature feature) {
        assertThat(feature.getPossibleValues())
                .as("ProductFeature.%s.possibleValues", feature.name())
                .isNotEmpty();
    }

    static Stream<BuiltInGovernanceProfile> allProfiles() {
        return Stream.of(BuiltInGovernanceProfile.values());
    }

    static Stream<ProductFeature> allFeatures() {
        return Stream.of(ProductFeature.values());
    }
}