// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BuiltInGovernanceProfile} — completeness (every feature
 * set in every profile), values match {@code CONTEXT-AND-GOVERNANCE.md} §4.1,
 * and the built-in order forms the expected strictness chain.
 */
class BuiltInGovernanceProfileTest {

    // ── completeness ─────────────────────────────────────────────────

    @Test
    void everyFeatureHasAValueInEveryProfile() {
        for (var profile : BuiltInGovernanceProfile.values()) {
            for (var feature : ProductFeature.values()) {
                assertThat(profile.featureValues())
                        .as("%s missing value for %s", profile.code(), feature.name())
                        .containsKey(feature);
                assertThat(profile.featureValues().get(feature))
                        .as("%s has empty set for %s", profile.code(), feature.name())
                        .isNotEmpty();
            }
        }
    }

    // ── STRICT values (§4.1) ────────────────────────────────────────

    @Test
    void strictValuesMatchMatrix() {
        var v = BuiltInGovernanceProfile.STRICT.featureValues();
        assertThat(v.get(ProductFeature.INSTRUCTION_SOURCES)).containsExactly("GIT");
        assertThat(v.get(ProductFeature.INLINE_INSTRUCTIONS_SCOPE)).containsExactly("NONE");
        assertThat(v.get(ProductFeature.KNOWLEDGE_PROVIDERS)).containsExactly("MANAGED");
        assertThat(v.get(ProductFeature.DATA_FEEDS)).containsExactly("GIT");
        assertThat(v.get(ProductFeature.MANIFEST_RETENTION)).containsExactly("365_DAYS");
        assertThat(v.get(ProductFeature.MANIFEST_FREQUENCY)).containsExactly("FULL");
        assertThat(v.get(ProductFeature.CONTEXT_PINNING)).containsExactly("ON");
        assertThat(v.get(ProductFeature.ACTIVATION_RULES)).containsExactly("REQUIRED_ALL");
        assertThat(v.get(ProductFeature.BUDGET_ENFORCEMENT)).containsExactly("ON");
        assertThat(v.get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("NONE");
    }

    // ── STANDARD values (§4.1) ──────────────────────────────────────

    @Test
    void standardValuesMatchMatrix() {
        var v = BuiltInGovernanceProfile.STANDARD.featureValues();
        assertThat(v.get(ProductFeature.INSTRUCTION_SOURCES)).containsExactlyInAnyOrder("INLINE", "GIT");
        assertThat(v.get(ProductFeature.INLINE_INSTRUCTIONS_SCOPE)).containsExactly("PROJECT_SERVICE");
        assertThat(v.get(ProductFeature.KNOWLEDGE_PROVIDERS)).containsExactlyInAnyOrder("MANAGED", "EXTERNAL");
        assertThat(v.get(ProductFeature.DATA_FEEDS)).containsExactlyInAnyOrder("GIT", "CONFLUENCE", "JIRA", "NOTION");
        assertThat(v.get(ProductFeature.MANIFEST_RETENTION)).containsExactly("90_DAYS");
        assertThat(v.get(ProductFeature.MANIFEST_FREQUENCY)).containsExactly("FULL");
        assertThat(v.get(ProductFeature.CONTEXT_PINNING)).containsExactly("ON");
        assertThat(v.get(ProductFeature.ACTIVATION_RULES)).containsExactly("REQUIRED_ORG_PROJECT");
        assertThat(v.get(ProductFeature.BUDGET_ENFORCEMENT)).containsExactly("ON");
        assertThat(v.get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("PER_SERVICE");
    }

    // ── FLEXIBLE values (§4.1) ──────────────────────────────────────

    @Test
    void flexibleValuesMatchMatrix() {
        var v = BuiltInGovernanceProfile.FLEXIBLE.featureValues();
        assertThat(v.get(ProductFeature.INSTRUCTION_SOURCES)).containsExactlyInAnyOrder("INLINE", "GIT");
        assertThat(v.get(ProductFeature.INLINE_INSTRUCTIONS_SCOPE)).containsExactly("ALL");
        assertThat(v.get(ProductFeature.KNOWLEDGE_PROVIDERS)).containsExactlyInAnyOrder("MANAGED", "EXTERNAL");
        assertThat(v.get(ProductFeature.DATA_FEEDS)).containsExactlyInAnyOrder(
                "GIT", "WEB_CRAWL", "CONFLUENCE", "JIRA", "NOTION", "S3", "DB_SCHEMA");
        assertThat(v.get(ProductFeature.MANIFEST_RETENTION)).containsExactly("30_DAYS");
        assertThat(v.get(ProductFeature.MANIFEST_FREQUENCY)).containsExactly("SAMPLED");
        assertThat(v.get(ProductFeature.CONTEXT_PINNING)).containsExactly("OFF");
        assertThat(v.get(ProductFeature.ACTIVATION_RULES)).containsExactly("OPTIONAL");
        assertThat(v.get(ProductFeature.BUDGET_ENFORCEMENT)).containsExactly("ON");
        assertThat(v.get(ProductFeature.BUDGET_OVERRIDE)).containsExactly("CONFIGURABLE");
    }

    // ── BUDGET_ENFORCEMENT invariant ─────────────────────────────────

    @Test
    void budgetEnforcementIsOnInAllProfiles() {
        for (var p : BuiltInGovernanceProfile.values()) {
            assertThat(p.featureValues().get(ProductFeature.BUDGET_ENFORCEMENT))
                    .as("%s BUDGET_ENFORCEMENT", p.code())
                    .containsExactly("ON");
        }
    }

    // ── byCode ──────────────────────────────────────────────────────

    @Test
    void byCode_resolvesKnownCodes() {
        assertThat(BuiltInGovernanceProfile.byCode("STRICT")).isEqualTo(BuiltInGovernanceProfile.STRICT);
        assertThat(BuiltInGovernanceProfile.byCode("STANDARD")).isEqualTo(BuiltInGovernanceProfile.STANDARD);
        assertThat(BuiltInGovernanceProfile.byCode("FLEXIBLE")).isEqualTo(BuiltInGovernanceProfile.FLEXIBLE);
    }

    @Test
    void byCode_returnsNullForUnknownCode() {
        assertThat(BuiltInGovernanceProfile.byCode("CUSTOM")).isNull();
    }

    // ── builtIn flag ───────────────────────────────────────────────

    @Test
    void builtInFlagIsAlwaysTrue() {
        for (var p : BuiltInGovernanceProfile.values()) {
            assertThat(p.builtIn()).isTrue();
        }
    }

    // ── strictness chain ───────────────────────────────────────────

    @Test
    void strictnessChain_strictStandardFlexible() {
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STRICT, BuiltInGovernanceProfile.STANDARD)).isTrue();
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.STANDARD, BuiltInGovernanceProfile.FLEXIBLE)).isTrue();
        // Reverse is not strict
        assertThat(FeatureStrictness.atLeastAsStrict(
                BuiltInGovernanceProfile.FLEXIBLE, BuiltInGovernanceProfile.STRICT)).isFalse();
    }
}