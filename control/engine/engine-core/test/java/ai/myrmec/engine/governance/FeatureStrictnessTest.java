// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FeatureStrictness} — single-value index ordering,
 * multi-value subset, and profile-level partial order.
 */
class FeatureStrictnessTest {

    // ── single-value ────────────────────────────────────────────────

    @Test
    void singleValue_lowerIndexIsStricter() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.MANIFEST_RETENTION, "365_DAYS", "90_DAYS")).isTrue();
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.MANIFEST_RETENTION, "365_DAYS", "30_DAYS")).isTrue();
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.MANIFEST_RETENTION, "90_DAYS", "365_DAYS")).isFalse();
    }

    @Test
    void singleValue_sameValueIsAtLeastAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.CONTEXT_PINNING, "ON", "ON")).isTrue();
    }

    @Test
    void singleValue_unknownValueThrows() {
        assertThatThrownBy(() ->
                FeatureStrictness.atLeastAsStrict(ProductFeature.MANIFEST_RETENTION, "BAD", "90_DAYS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── multi-value (subset) ────────────────────────────────────────

    @Test
    void multiValue_subsetIsStricter() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.INSTRUCTION_SOURCES,
                Set.of("GIT"),
                Set.of("INLINE", "GIT"))).isTrue();
    }

    @Test
    void multiValue_supersetIsNotStricter() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.INSTRUCTION_SOURCES,
                Set.of("INLINE", "GIT"),
                Set.of("GIT"))).isFalse();
    }

    @Test
    void multiValue_equalSetsAreAtLeastAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                ProductFeature.DATA_FEEDS,
                Set.of("GIT", "CONFLUENCE"),
                Set.of("GIT", "CONFLUENCE"))).isTrue();
    }

    // ── profile-level (partial order) ───────────────────────────────

    @Test
    void profile_strictIsAtLeastAsStrictAsStandard() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT,
                BuiltInGovernanceProfile.STANDARD)).isTrue();
    }

    @Test
    void profile_standardIsAtLeastAsStrictAsFlexible() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STANDARD,
                BuiltInGovernanceProfile.FLEXIBLE)).isTrue();
    }

    @Test
    void profile_strictIsAtLeastAsStrictAsFlexible() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT,
                BuiltInGovernanceProfile.FLEXIBLE)).isTrue();
    }

    @Test
    void profile_flexibleIsNotAtLeastAsStrictAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.FLEXIBLE,
                BuiltInGovernanceProfile.STRICT)).isFalse();
    }

    @Test
    void profile_standardIsNotAtLeastAsStrictAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STANDARD,
                BuiltInGovernanceProfile.STRICT)).isFalse();
    }

    @Test
    void profile_sameProfileIsAtLeastAsStrictAsItself() {
        for (var p : BuiltInGovernanceProfile.values()) {
            assertThat(FeatureStrictness.atLeastAsStrict(p, p)).isTrue();
        }
    }
}