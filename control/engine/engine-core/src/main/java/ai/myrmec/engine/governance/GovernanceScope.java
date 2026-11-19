// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.UUID;

/**
 * Carries the scope for governance-policy resolution.
 *
 * <p>Null {@code teamId} ⇒ org scope (the org-level default profile).
 * A non-null {@code teamId} resolves to the Team-level selection if
 * present (future), else falls back to the org default.
 *
 * @param teamId the Team id (nullable — null means org scope)
 * @param groupId   the group id (nullable — reserved for future group-scoped profiles)
 */
public record GovernanceScope(UUID teamId, UUID groupId) {

    /** Org-wide scope (no Team, no group). */
    public static GovernanceScope orgScope() {
        return new GovernanceScope(null, null);
    }

    /** Team-scoped. */
    public static GovernanceScope ofTeam(UUID teamId) {
        return new GovernanceScope(teamId, null);
    }

    /** Whether this is org-wide (no Team). */
    public boolean isOrgScope() {
        return teamId == null;
    }
}