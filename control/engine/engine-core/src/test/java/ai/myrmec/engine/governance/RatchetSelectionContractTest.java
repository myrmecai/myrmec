// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-02: Governance profile ratchet selection contract.
 *
 * <p>Verifies that the ratchet rule (a child profile must be at least as
 * strict as its parent) is enforced by {@link FeatureStrictness#atLeastAsStrict}.
 *
 * <p>STRICT ≥ STANDARD ≥ FLEXIBLE in strictness ordering. A project may
 * tighten (STANDARD → STRICT) but not loosen (STRICT → FLEXIBLE).
 */
@Tag("RECON-02")
@Tag("SG5")
@DisplayName("RECON-02: Governance Profile Ratchet Selection")
class RatchetSelectionContractTest {

    @Test
    @DisplayName("STRICT is at least as strict as STRICT")
    void strictAsStrictAsItself() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT,
                BuiltInGovernanceProfile.STRICT)).isTrue();
    }

    @Test
    @DisplayName("STRICT is at least as strict as STANDARD")
    void strictAsStrictAsStandard() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT,
                BuiltInGovernanceProfile.STANDARD)).isTrue();
    }

    @Test
    @DisplayName("STRICT is at least as strict as FLEXIBLE")
    void strictAsStrictAsFlexible() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT,
                BuiltInGovernanceProfile.FLEXIBLE)).isTrue();
    }

    @Test
    @DisplayName("STANDARD is NOT as strict as STRICT (loosening rejected)")
    void standardNotAsStrictAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STANDARD,
                BuiltInGovernanceProfile.STRICT)).isFalse();
    }

    @Test
    @DisplayName("FLEXIBLE is NOT as strict as STRICT (loosening rejected)")
    void flexibleNotAsStrictAsStrict() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.FLEXIBLE,
                BuiltInGovernanceProfile.STRICT)).isFalse();
    }

    @Test
    @DisplayName("STANDARD is at least as strict as FLEXIBLE (tightening allowed)")
    void standardAsStrictAsFlexible() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STANDARD,
                BuiltInGovernanceProfile.FLEXIBLE)).isTrue();
    }

    @Test
    @DisplayName("FLEXIBLE is NOT as strict as STANDARD (loosening rejected)")
    void flexibleNotAsStrictAsStandard() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.FLEXIBLE,
                BuiltInGovernanceProfile.STANDARD)).isFalse();
    }
}