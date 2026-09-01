// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.Map;
import java.util.Set;

/**
 * Contract for a governance profile definition — the values a profile sets
 * for each {@link ProductFeature}.
 *
 * <p>Implemented by built-in profiles ({@code BuiltInGovernanceProfile},
 * landing in T2) and, in the future, by custom DB-backed profiles. The
 * {@link GovernanceProfileResponse} DTO's canonical factory accepts this
 * interface; until T2 lands, a transitional {@code from(GovernanceProfile)}
 * overload bridges the legacy entity.</p>
 *
 * @see GovernanceProfileResponse
 */
public interface GovernanceProfileDefinition {

    /** Profile code (e.g. {@code STRICT}, {@code STANDARD}, {@code FLEXIBLE}). */
    String code();

    /** Display name. */
    String displayName();

    /** Whether this is a built-in (immutable, code-defined) profile. */
    boolean builtIn();

    /**
     * The feature-value map. Single-value features hold a 1-element set.
     */
    Map<ProductFeature, Set<String>> featureValues();
}