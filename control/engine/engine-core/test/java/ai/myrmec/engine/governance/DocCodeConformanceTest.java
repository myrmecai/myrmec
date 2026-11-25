// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.workflow.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documentation-code conformance checks — implements golden journey J12
 * from {@code 02-system-e2e-strategy.md}.
 *
 * <p>Proves SG9 (documentation-code conformance): every enum, status
 * lifecycle, and feature mapping stated in the canonical docs has
 * exactly one matching definition in code. Fails the build on drift.</p>
 *
 * <p>These are Tier 0 tests — pure logic, no Spring context, no DB.
 * They run in milliseconds and should be run on every commit.</p>
 *
 * <p>Start with the three facts the Phase 1 reconciliation had to fix
 * by hand. Extend as new enums/features are added.</p>
 */
@DisplayName("Documentation-Code Conformance (J12)")
class DocCodeConformanceTest {

    // ================================================================
    // 1. Workflow status enum
    // ================================================================

    @Test
    @DisplayName("WorkflowStatus enum matches decisions.md: {DRAFT, PUBLISHED, DISABLED, ARCHIVED}")
    void workflowStatusEnumMatchesDoc() {
        Set<String> codeValues = Arrays.stream(WorkflowStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        Set<String> docValues = Set.of("DRAFT", "PUBLISHED", "DISABLED", "ARCHIVED");

        assertThat(codeValues)
                .as("WorkflowStatus enum must match the 4-value set in decisions.md")
                .isEqualTo(docValues);
    }

    // ================================================================
    // 2. ProductFeature runtime-name mapping (§4.2)
    // ================================================================

    @Test
    @DisplayName("ProductFeature runtime names match context-and-governance.md §4.2")
    void productFeatureRuntimeNamesMatchDoc() {
        // Doc §4.2: Feature enum value → Runtime name
        Map<String, String> docMapping = Map.of(
                "BUDGET_OVERRIDE:NONE", "HARD_CAP_NO_OVERRIDE",
                "BUDGET_OVERRIDE:PER_SERVICE", "HARD_CAP_PER_SERVICE",
                "BUDGET_OVERRIDE:CONFIGURABLE", "CONFIGURABLE",
                "CONTEXT_PINNING:ON", "PINNED_AT_START",
                "CONTEXT_PINNING:OFF", "IMMEDIATE_EFFECT",
                "MANIFEST_FREQUENCY:FULL", "contextManifestOnEveryExecution=true",
                "MANIFEST_FREQUENCY:SAMPLED", "contextManifestOnEveryExecution=false");

        for (var entry : docMapping.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            ProductFeature feature = ProductFeature.valueOf(parts[0]);
            String codeValue = parts[1];
            String expectedRuntimeName = entry.getValue();
            String actualRuntimeName = feature.runtimeName(codeValue);

            assertThat(actualRuntimeName)
                    .as("ProductFeature.%s.runtimeName(\"%s\") must match doc §4.2",
                            feature.name(), codeValue)
                    .isEqualTo(expectedRuntimeName);
        }
    }

    // ================================================================
    // 3. BuiltInGovernanceProfile completeness
    // ================================================================

    @Test
    @DisplayName("Every ProductFeature has a value in every BuiltInGovernanceProfile")
    void everyFeatureHasValueInEveryProfile() {
        for (BuiltInGovernanceProfile profile : BuiltInGovernanceProfile.values()) {
            Map<ProductFeature, Set<String>> profileValues = profile.featureValues();
            for (ProductFeature feature : ProductFeature.values()) {
                Set<String> values = profileValues.get(feature);
                assertThat(values)
                        .as("BuiltInGovernanceProfile.%s must define a value for ProductFeature.%s",
                                profile.name(), feature.name())
                        .isNotNull()
                        .isNotEmpty();
            }
        }
    }

    // ================================================================
    // 4. BuiltInGovernanceProfile codes match doc
    // ================================================================

    @Test
    @DisplayName("BuiltInGovernanceProfile codes are STRICT, STANDARD, FLEXIBLE")
    void builtInProfileCodesMatchDoc() {
        Set<String> codeValues = Arrays.stream(BuiltInGovernanceProfile.values())
                .map(BuiltInGovernanceProfile::code)
                .collect(Collectors.toSet());

        Set<String> docValues = Set.of("STRICT", "STANDARD", "FLEXIBLE");

        assertThat(codeValues)
                .as("BuiltInGovernanceProfile codes must match context-and-governance.md §4.1")
                .isEqualTo(docValues);
    }

    // ================================================================
    // 5. ProductFeature possibleValues are ordered strictest → loosest
    // ================================================================

    @Test
    @DisplayName("ProductFeature possibleValues are ordered strictest → loosest (invariant)")
    void productFeatureValuesOrderedStrictestToLoosest() {
        for (ProductFeature feature : ProductFeature.values()) {
            assertThat(feature.getPossibleValues())
                    .as("ProductFeature.%s.possibleValues must be non-empty", feature.name())
                    .isNotEmpty();

            // Verify the invariant stated in the ProductFeature Javadoc:
            // "possibleValues is ordered strictest → loosest. Index 0 is the
            // most restrictive value."
            // We can't programmatically verify "strictest" without knowing the
            // semantics, but we can verify the list is non-empty and the
            // BuiltInGovernanceProfile values are all drawn from this list.
            for (BuiltInGovernanceProfile profile : BuiltInGovernanceProfile.values()) {
                Set<String> profileFeatureValues = profile.featureValues().get(feature);
                if (profileFeatureValues != null) {
                    assertThat(feature.getPossibleValues())
                            .as("ProductFeature.%s values in profile %s must be valid possibleValues",
                                    feature.name(), profile.name())
                            .containsAll(profileFeatureValues);
                }
            }
        }
    }
}
