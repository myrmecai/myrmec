// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

/**
 * Permission snapshot for budget/quota operations at a single scope.
 *
 * <p>Returned by {@link ai.myrmec.engine.quota.BudgetAuthorization} so callers
 * can decide whether to render create/edit/pause/delete actions in the UI or
 * reject requests at the controller layer.</p>
 */
public record BudgetPermissions(
        boolean canView,
        boolean canCreateCeiling,
        boolean canCreateReservation,
        boolean canCreateServiceBudget,
        boolean canPauseResume,
        boolean canDelete) {

    /** All flags false — anonymous or principal without any budget access. */
    public static BudgetPermissions none() {
        return new BudgetPermissions(false, false, false, false, false, false);
    }
}
