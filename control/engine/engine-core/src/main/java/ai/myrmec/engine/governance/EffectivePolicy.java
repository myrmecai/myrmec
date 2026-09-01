// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Typed, read-only view of the effective governance policy for a scope.
 *
 * <p>Built by {@link GovernancePolicyResolver} from a
 * {@link GovernanceProfileDefinition}. Consumers never see JSONB or
 * legacy strings — they ask via {@link #allows}, {@link #values}, or
 * {@link #single}.
 */
public class EffectivePolicy {

    private final String code;
    private final Map<ProductFeature, Set<String>> values;

    public EffectivePolicy(GovernanceProfileDefinition definition) {
        this.code = definition.code();
        this.values = Map.copyOf(definition.featureValues());
    }

    /** The resolved profile code (for audit/manifest). */
    public String code() {
        return code;
    }

    /** All values for a feature (multi-value features return >1 element). */
    public Set<String> values(ProductFeature f) {
        return values.getOrDefault(f, Collections.emptySet());
    }

    /** The single value for a single-value feature. */
    public String single(ProductFeature f) {
        Set<String> v = values(f);
        if (v.isEmpty()) {
            throw new IllegalStateException("No value for " + f.name() + " in profile " + code);
        }
        return v.iterator().next();
    }

    /** Whether a value is allowed for the given feature (set membership). */
    public boolean allows(ProductFeature f, String value) {
        return values(f).contains(value);
    }

    /**
     * Whether the profile's single-value setting permits at least the given
     * threshold. Uses the {@link ProductFeature#getPossibleValues()} ordering
     * (strict→loose): the profile's value must be at the same index or a
     * looser index than {@code required}.
     *
     * <p>For ordinal/threshold features like {@code INLINE_INSTRUCTIONS_SCOPE}
     * ({@code NONE < PROJECT_SERVICE < ALL}) and {@code BUDGET_OVERRIDE}
     * ({@code NONE < PER_SERVICE < CONFIGURABLE}), use this instead of
     * {@link #allows} — a profile set to {@code ALL} should permit
     * {@code PROJECT_SERVICE} even though {@code ALL != PROJECT_SERVICE}.
     *
     * @param f        a single-value feature
     * @param required  the minimum permission level being requested
     * @return true if the profile's value is at least as permissive as required
     */
    public boolean permitsAtLeast(ProductFeature f, String required) {
        String profileValue = single(f);
        int profileIdx = f.getPossibleValues().indexOf(profileValue);
        int requiredIdx = f.getPossibleValues().indexOf(required);
        if (profileIdx < 0 || requiredIdx < 0) {
            throw new IllegalArgumentException(
                    "Unknown value for " + f.name() + ": profile='" + profileValue + "', required='" + required + "'");
        }
        // Loosest is at the highest index; "at least as permissive" means
        // profileIdx >= requiredIdx.
        return profileIdx >= requiredIdx;
    }
}