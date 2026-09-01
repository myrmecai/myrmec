// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves a governance-profile code to a {@link GovernanceProfileDefinition}.
 *
 * <p>Looks up the built-in registry first ({@link BuiltInGovernanceProfile#byCode}),
 * then (future) the custom DB store. This is the only place that knows
 * built-in vs custom — the resolver, enforcer, and consumers all work
 * through the {@link GovernanceProfileDefinition} interface.
 */
@Slf4j
@Component
public class GovernanceProfileProvider {

    /**
     * Resolve a code to its definition.
     *
     * @param code the profile code (e.g. {@code STRICT}, {@code STANDARD}, {@code FLEXIBLE})
     * @return the definition
     * @throws ResourceNotFoundException if the code is not a built-in and (future)
     *         no custom profile exists for it
     */
    public GovernanceProfileDefinition resolve(String code) {
        BuiltInGovernanceProfile builtIn = BuiltInGovernanceProfile.byCode(code);
        if (builtIn != null) {
            return builtIn;
        }
        // Future: look up custom profiles from DB
        throw ResourceNotFoundException.of("GovernanceProfile", code);
    }

    /**
     * Whether a code resolves to a known profile.
     */
    public boolean exists(String code) {
        return BuiltInGovernanceProfile.byCode(code) != null;
    }
}