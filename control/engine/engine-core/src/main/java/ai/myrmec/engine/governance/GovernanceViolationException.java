// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import lombok.Getter;

import java.util.Set;

/**
 * Thrown by {@link GovernancePolicyEnforcer#assertAllowed} when a governed
 * action violates the effective governance profile.
 *
 * <p>Mapped to HTTP 403 with error code {@code GOVERNANCE_VIOLATION} and a
 * {@code details} object carrying {@code feature}, {@code attemptedValue},
 * {@code allowedValues}, and {@code profileCode}.
 */
@Getter
public class GovernanceViolationException extends RuntimeException {

    private final ProductFeature feature;
    private final String attemptedValue;
    private final Set<String> allowedValues;
    private final String profileCode;

    public GovernanceViolationException(ProductFeature feature, String attemptedValue,
                                        Set<String> allowedValues, String profileCode) {
        super(String.format(
                "Governance violation: feature=%s, attemptedValue=%s, allowedValues=%s, profile=%s",
                feature.name(), attemptedValue, allowedValues, profileCode));
        this.feature = feature;
        this.attemptedValue = attemptedValue;
        this.allowedValues = allowedValues;
        this.profileCode = profileCode;
    }
}