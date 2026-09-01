// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Enforces governance policy at action points.
 *
 * <p>For {@link EnforcementKind#BLOCKING} features, call
 * {@link #assertAllowed} at the service/controller entry point. A violation
 * throws {@link GovernanceViolationException} (mapped to HTTP 403
 * {@code GOVERNANCE_VIOLATION}).
 *
 * <p>For {@link EnforcementKind#BEHAVIORAL} features, the consumer reads
 * {@link EffectivePolicy#single} or {@link ProductFeature#runtimeName}
 * directly — the enforcer is not involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernancePolicyEnforcer {

    private final GovernancePolicyResolver resolver;

    /**
     * Whether a value is allowed for the given feature at the given scope.
     */
    public boolean isAllowed(GovernanceScope scope, ProductFeature f, String value) {
        EffectivePolicy policy = resolver.resolve(scope);
        return policy.allows(f, value);
    }

    /**
     * Assert that a value is allowed for the given feature at the given
     * scope. Throws {@link GovernanceViolationException} on violation.
     */
    public void assertAllowed(GovernanceScope scope, ProductFeature f, String value) {
        EffectivePolicy policy = resolver.resolve(scope);
        if (!policy.allows(f, value)) {
            Set<String> allowed = policy.values(f);
            log.warn("Governance violation: feature={}, attempted={}, allowed={}, profile={}",
                    f.name(), value, allowed, policy.code());
            throw new GovernanceViolationException(f, value, allowed, policy.code());
        }
    }

    /**
     * Assert that the profile's single-value setting permits at least the
     * given threshold. For ordinal/threshold features like
     * {@code INLINE_INSTRUCTIONS_SCOPE} and {@code BUDGET_OVERRIDE} —
     * a profile set to {@code ALL} should permit {@code PROJECT_SERVICE}.
     *
     * @see EffectivePolicy#permitsAtLeast
     */
    public void assertPermitted(GovernanceScope scope, ProductFeature f, String required) {
        EffectivePolicy policy = resolver.resolve(scope);
        if (!policy.permitsAtLeast(f, required)) {
            Set<String> allowed = policy.values(f);
            log.warn("Governance violation (threshold): feature={}, required={}, allowed={}, profile={}",
                    f.name(), required, allowed, policy.code());
            throw new GovernanceViolationException(f, required, allowed, policy.code());
        }
    }
}