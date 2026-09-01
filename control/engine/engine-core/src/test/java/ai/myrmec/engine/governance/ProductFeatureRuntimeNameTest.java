// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-10: Verify that {@link ProductFeature#runtimeName(String)} matches
 * the {@code CONTEXT-AND-GOVERNANCE.md} §4.2 runtime-name mapping table for
 * every mapped value. Stops name drift from re-appearing after the
 * {@code extractValues} shim was deleted.
 */
class ProductFeatureRuntimeNameTest {

    @ParameterizedTest
    @CsvSource({
            // CONTEXT_PINNING (§4.2)
            "CONTEXT_PINNING, ON, PINNED_AT_START",
            "CONTEXT_PINNING, OFF, IMMEDIATE_EFFECT",
            // BUDGET_OVERRIDE (§4.2)
            "BUDGET_OVERRIDE, NONE, HARD_CAP_NO_OVERRIDE",
            "BUDGET_OVERRIDE, PER_SERVICE, HARD_CAP_PER_SERVICE",
            "BUDGET_OVERRIDE, CONFIGURABLE, CONFIGURABLE",
            // MANIFEST_FREQUENCY (§4.2)
            "MANIFEST_FREQUENCY, FULL, contextManifestOnEveryExecution=true",
            "MANIFEST_FREQUENCY, SAMPLED, contextManifestOnEveryExecution=false",
    })
    void runtimeNameMatchesMappingTable(ProductFeature feature, String value, String expectedRuntimeName) {
        assertThat(feature.runtimeName(value))
                .as("%s.%s runtime name", feature.name(), value)
                .isEqualTo(expectedRuntimeName);
    }

    @ParameterizedTest
    @CsvSource({
            // Values with no alias pass through unchanged
            "INSTRUCTION_SOURCES, INLINE",
            "INSTRUCTION_SOURCES, GIT",
            "DATA_FEEDS, GIT",
            "BUDGET_ENFORCEMENT, ON",
            "MANIFEST_RETENTION, 365_DAYS",
    })
    void runtimeNamePassThroughWhenNoAlias(ProductFeature feature, String value) {
        assertThat(feature.runtimeName(value)).isEqualTo(value);
    }
}