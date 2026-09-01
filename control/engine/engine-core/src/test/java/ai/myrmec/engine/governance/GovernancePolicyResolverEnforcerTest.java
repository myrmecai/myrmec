// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link GovernancePolicyResolver} and
 * {@link GovernancePolicyEnforcer} on the e2e profile (H2).
 */
class GovernancePolicyResolverEnforcerTest extends IntegrationTestBase {

    @Autowired
    private GovernancePolicyResolver resolver;

    @Autowired
    private GovernancePolicyEnforcer enforcer;

    @Autowired
    private GovernanceProfileService profileService;

    @Test
    void resolveOrgDefaultReturnsStandardByDefault() {
        EffectivePolicy policy = resolver.resolveOrgDefault();
        assertThat(policy.code()).isEqualTo("STANDARD");
    }

    @Test
    void resolveReturnsProfileValues() {
        setDefaultProfile("STRICT");
        try {
            EffectivePolicy policy = resolver.resolveOrgDefault();
            assertThat(policy.code()).isEqualTo("STRICT");
            assertThat(policy.allows(ProductFeature.INSTRUCTION_SOURCES, "GIT")).isTrue();
            assertThat(policy.allows(ProductFeature.INSTRUCTION_SOURCES, "INLINE")).isFalse();
            assertThat(policy.single(ProductFeature.CONTEXT_PINNING)).isEqualTo("ON");
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void enforcerAllowsWhenProfilePermits() {
        setDefaultProfile("STANDARD");
        // STANDARD allows INLINE instruction sources
        enforcer.assertAllowed(GovernanceScope.orgScope(),
                ProductFeature.INSTRUCTION_SOURCES, "INLINE");
        // Should not throw — that's the assertion
    }

    @Test
    void enforcerThrowsViolationWhenProfileDenies() {
        setDefaultProfile("STRICT");
        try {
            assertThatThrownBy(() ->
                    enforcer.assertAllowed(GovernanceScope.orgScope(),
                            ProductFeature.INSTRUCTION_SOURCES, "INLINE"))
                    .isInstanceOf(GovernanceViolationException.class)
                    .satisfies(ex -> {
                        GovernanceViolationException gve = (GovernanceViolationException) ex;
                        assertThat(gve.getFeature()).isEqualTo(ProductFeature.INSTRUCTION_SOURCES);
                        assertThat(gve.getAttemptedValue()).isEqualTo("INLINE");
                        assertThat(gve.getProfileCode()).isEqualTo("STRICT");
                    });
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void isAllowedReturnsFalseWithoutThrowing() {
        setDefaultProfile("STRICT");
        try {
            assertThat(enforcer.isAllowed(GovernanceScope.orgScope(),
                    ProductFeature.KNOWLEDGE_PROVIDERS, "EXTERNAL")).isFalse();
            assertThat(enforcer.isAllowed(GovernanceScope.orgScope(),
                    ProductFeature.KNOWLEDGE_PROVIDERS, "MANAGED")).isTrue();
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void ratchetValidReturnsTrueForStricterOrEqual() {
        assertThat(resolver.isRatchetValid("STRICT")).isTrue();  // STRICT >= STANDARD
        assertThat(resolver.isRatchetValid("STANDARD")).isTrue();  // STANDARD >= STANDARD
    }

    @Test
    void ratchetValidReturnsFalseForLooser() {
        setDefaultProfile("STRICT");
        try {
            assertThat(resolver.isRatchetValid("STANDARD")).isFalse();  // STANDARD < STRICT
            assertThat(resolver.isRatchetValid("FLEXIBLE")).isFalse();  // FLEXIBLE < STRICT
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    private void setDefaultProfile(String code) {
        profileService.setDefaultProfile(code, null);
    }

    // ── threshold/ordinal features (permitsAtLeast) ────────────────

    @Test
    void permitsAtLeast_inlineScope_flexibleAllPermitsProjectService() {
        setDefaultProfile("FLEXIBLE");
        try {
            // FLEXIBLE=ALL, checking if it permits PROJECT_SERVICE → should be true
            EffectivePolicy policy = resolver.resolveOrgDefault();
            assertThat(policy.permitsAtLeast(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "PROJECT_SERVICE")).isTrue();
            assertThat(policy.permitsAtLeast(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "ALL")).isTrue();
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void permitsAtLeast_inlineScope_strictNoneRejectsProjectService() {
        setDefaultProfile("STRICT");
        try {
            EffectivePolicy policy = resolver.resolveOrgDefault();
            assertThat(policy.permitsAtLeast(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "PROJECT_SERVICE")).isFalse();
            assertThat(policy.permitsAtLeast(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "ALL")).isFalse();
            // NONE permits NONE (itself)
            assertThat(policy.permitsAtLeast(ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "NONE")).isTrue();
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void permitsAtLeast_budgetOverride_flexibleConfigurablePermitsPerService() {
        setDefaultProfile("FLEXIBLE");
        try {
            EffectivePolicy policy = resolver.resolveOrgDefault();
            assertThat(policy.permitsAtLeast(ProductFeature.BUDGET_OVERRIDE, "PER_SERVICE")).isTrue();
            assertThat(policy.permitsAtLeast(ProductFeature.BUDGET_OVERRIDE, "CONFIGURABLE")).isTrue();
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void permitsAtLeast_budgetOverride_strictNoneRejectsPerService() {
        setDefaultProfile("STRICT");
        try {
            EffectivePolicy policy = resolver.resolveOrgDefault();
            assertThat(policy.permitsAtLeast(ProductFeature.BUDGET_OVERRIDE, "PER_SERVICE")).isFalse();
            assertThat(policy.permitsAtLeast(ProductFeature.BUDGET_OVERRIDE, "NONE")).isTrue();
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void assertPermitted_flexibleDoesNotThrowForProjectService() {
        setDefaultProfile("FLEXIBLE");
        try {
            enforcer.assertPermitted(GovernanceScope.orgScope(),
                    ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "PROJECT_SERVICE");
            enforcer.assertPermitted(GovernanceScope.orgScope(),
                    ProductFeature.BUDGET_OVERRIDE, "PER_SERVICE");
        } finally {
            setDefaultProfile("STANDARD");
        }
    }

    @Test
    void assertPermitted_strictThrowsForProjectService() {
        setDefaultProfile("STRICT");
        try {
            assertThatThrownBy(() ->
                    enforcer.assertPermitted(GovernanceScope.orgScope(),
                            ProductFeature.INLINE_INSTRUCTIONS_SCOPE, "PROJECT_SERVICE"))
                    .isInstanceOf(GovernanceViolationException.class);
        } finally {
            setDefaultProfile("STANDARD");
        }
    }
}