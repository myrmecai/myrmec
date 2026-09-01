// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import java.util.UUID;

/**
 * Carries the scope for governance-policy resolution.
 *
 * <p>Null {@code projectId} ⇒ org scope (the org-level default profile).
 * A non-null {@code projectId} resolves to the project-level selection if
 * present (future), else falls back to the org default.
 *
 * @param projectId the project id (nullable — null means org scope)
 * @param groupId   the group id (nullable — reserved for future group-scoped profiles)
 */
public record GovernanceScope(UUID projectId, UUID groupId) {

    /** Org-wide scope (no project, no group). */
    public static GovernanceScope orgScope() {
        return new GovernanceScope(null, null);
    }

    /** Project-scoped. */
    public static GovernanceScope ofProject(UUID projectId) {
        return new GovernanceScope(projectId, null);
    }

    /** Whether this is org-wide (no project). */
    public boolean isOrgScope() {
        return projectId == null;
    }
}