// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.Set;

/**
 * Pure functions over {@link ProductFeature} for strictness comparison (G5).
 *
 * <p>Strictness is derived from {@link ProductFeature#getPossibleValues()}
 * order (index 0 = strictest):
 * <ul>
 *   <li><b>Single-value</b> feature: value {@code a} is at-least-as-strict-as
 *       value {@code b} iff {@code indexOf(a) <= indexOf(b)}.</li>
 *   <li><b>Multi-value</b> feature: set {@code a} is at-least-as-strict-as
 *       set {@code b} iff {@code a ⊆ b} (subset).</li>
 * </ul>
 *
 * <p>Profile-level comparison: {@code a} is at-least-as-strict-as {@code b}
 * iff for <b>every</b> feature, {@code a}'s value(s) are at-least-as-strict-as
 * {@code b}'s. This is a <b>partial order</b> — two profiles can be
 * incomparable (e.g. one is stricter on feature X but looser on feature Y).
 * The ratchet treats incomparable as "rejected".
 */
public final class FeatureStrictness {

    private FeatureStrictness() {}

    /**
     * Single-value strictness: {@code a} is at-least-as-strict-as {@code b}
     * iff {@code indexOf(a) <= indexOf(b)} in {@link ProductFeature#getPossibleValues()}.
     */
    public static boolean atLeastAsStrict(ProductFeature f, String a, String b) {
        int ia = f.getPossibleValues().indexOf(a);
        int ib = f.getPossibleValues().indexOf(b);
        if (ia < 0 || ib < 0) {
            throw new IllegalArgumentException(
                    "Unknown value for " + f.name() + ": '" + a + "' or '" + b + "'");
        }
        return ia <= ib;
    }

    /**
     * Multi-value strictness: {@code a} is at-least-as-strict-as {@code b}
     * iff {@code a ⊆ b} (every value in {@code a} is also in {@code b}).
     */
    public static boolean atLeastAsStrict(ProductFeature f, Set<String> a, Set<String> b) {
        // Validate all values are known
        for (String v : a) {
            if (f.getPossibleValues().indexOf(v) < 0) {
                throw new IllegalArgumentException(
                        "Unknown value for " + f.name() + ": '" + v + "'");
            }
        }
        for (String v : b) {
            if (f.getPossibleValues().indexOf(v) < 0) {
                throw new IllegalArgumentException(
                        "Unknown value for " + f.name() + ": '" + v + "'");
            }
        }
        return b.containsAll(a);
    }

    /**
     * Profile-level strictness: {@code a} is at-least-as-strict-as {@code b}
     * iff for every {@link ProductFeature}, {@code a}'s value(s) are
     * at-least-as-strict-as {@code b}'s.
     *
     * <p>This is a partial order — returns {@code false} for incomparable
     * pairs. The ratchet rejects incomparable selections.
     */
    public static boolean atLeastAsStrict(GovernanceProfileDefinition a,
                                          GovernanceProfileDefinition b) {
        for (ProductFeature f : ProductFeature.values()) {
            Set<String> av = a.featureValues().get(f);
            Set<String> bv = b.featureValues().get(f);
            if (av == null || bv == null) {
                throw new IllegalStateException(
                        "Missing value for " + f.name()
                                + " in profile " + (av == null ? a.code() : b.code()));
            }
            if (f.isMultiValue()) {
                if (!atLeastAsStrict(f, av, bv)) {
                    return false;
                }
            } else {
                if (!atLeastAsStrict(f, av.iterator().next(), bv.iterator().next())) {
                    return false;
                }
            }
        }
        return true;
    }
}