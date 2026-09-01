// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance.dto;

import ai.myrmec.engine.governance.GovernanceProfileDefinition;

import java.util.List;

/**
 * Governance Profile response — the compare-matrix payload for the UI.
 *
 * <p>After the governance-enforcement rewrite (G2/G3), built-in profiles are
 * hardcoded Java enums implementing {@link GovernanceProfileDefinition}.
 * The legacy raw-JSONB {@code policies} map, the {@code isBuiltIn}/{@code isSystem}
 * flags, and the DB timestamps ({@code createdAt}/{@code updatedAt}) have been
 * removed — the UI never consumed them and they have no source for in-code
 * built-ins. The response now carries only what the compare matrix renders.</p>
 *
 * @param code             profile code (STRICT, STANDARD, FLEXIBLE)
 * @param name             display name
 * @param description      short description
 * @param isCurrentDefault true if this is the current org-level default
 * @param groups           structured feature groups for the compare matrix
 */
public record GovernanceProfileResponse(
        String code,
        String name,
        String description,
        Boolean isCurrentDefault,
        List<FeatureGroupResponse> groups) {

    public static GovernanceProfileResponse from(GovernanceProfileDefinition d,
                                                  boolean isCurrentDefault,
                                                  List<FeatureGroupResponse> groups) {
        return new GovernanceProfileResponse(
                d.code(),
                d.displayName(),
                d.builtIn() ? builtInDescription(d.code()) : "",
                isCurrentDefault,
                groups);
    }

    /** Human-readable descriptions for the three built-in profiles. */
    private static String builtInDescription(String code) {
        return switch (code) {
            case "STRICT" -> "Banking / Compliance — maximum governance, Git-only sources, hard caps.";
            case "STANDARD" -> "Enterprise default — balanced governance, Git + Inline, managed + external providers.";
            case "FLEXIBLE" -> "Pilot / Startup — minimal governance, all source types, configurable budgets.";
            default -> "";
        };
    }
}