// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Phase 8d — budget dashboard and quota lifecycle.
 *
 * Verifies the budget dashboard loads the org-level tree, supports
 * creating/editing/pausing/resuming/deleting a project quota from the
 * project detail page, and returns to the dashboard.
 *
 * Arrange via API; assert via UI.
 */
test.describe('budget dashboard', () => {
  test('loads budget dashboard with summary cards and tree', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await adminPage.goto('/budgets')

    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible()

    await expect(
      adminPage.getByRole('link', { name: 'New Budget' }),
    ).toBeVisible()

    // Tree is rendered for the org scope (present by default).
    await expect(adminPage.getByText('Total Cost USD limit')).toBeVisible()
    await expect(adminPage.getByText('Consumed')).toBeVisible()
    await expect(adminPage.getByText('Remaining')).toBeVisible()
  })

  test('admin can create, edit, pause, resume and delete a project budget', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `budget-e2e-${Date.now()}`,
      description: 'Phase 8d budget lifecycle spec',
    })

    // Create budget from the dashboard.
    await adminPage.goto('/budgets')
    await adminPage.getByRole('link', { name: 'New Budget' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'New Budget' }),
    ).toBeVisible()

    await adminPage.getByLabel('Scope type').click()
    await adminPage.getByRole('option', { name: 'Project' }).click()
    await adminPage.getByLabel('Scope ID').fill(project.id)
    await adminPage.getByLabel('Limit').fill('50000')
    await adminPage.getByRole('button', { name: 'Create Budget' }).click()

    // Dashboard should show the new project row.
    await expect(
      adminPage.getByText(new RegExp(project.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))),
    ).toBeVisible({ timeout: 10_000 })

    // Navigate to project budget page.
    await adminPage.goto(`/budgets/projects/${project.id}`)
    await expect(
      adminPage.getByRole('heading', { name: `${project.name} budgets` }),
    ).toBeVisible()

    // Effective quota card is rendered.
    await expect(adminPage.getByText(/this month cost.*budget/i)).toBeVisible()
    await expect(adminPage.getByRole('button', { name: 'Edit' })).toBeVisible()

    // Edit the budget.
    await adminPage.getByRole('button', { name: 'Edit' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'Edit Budget' }),
    ).toBeVisible()

    await adminPage.getByLabel('Limit').fill('30000')
    await adminPage.getByRole('button', { name: 'Save Changes' }).click()

    // Back on the project page, the new limit is reflected.
    await expect(
      adminPage.getByRole('heading', { name: `${project.name} budgets` }),
    ).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByTestId('effective-limit')).toHaveText('$300.00')

    // Pause.
    await adminPage.getByRole('button', { name: 'Pause' }).click()
    await expect(adminPage.getByRole('button', { name: 'Resume' })).toBeVisible({
      timeout: 10_000,
    })

    // Resume.
    await adminPage.getByRole('button', { name: 'Resume' }).click()
    await expect(adminPage.getByRole('button', { name: 'Pause' })).toBeVisible({
      timeout: 10_000,
    })

    // Delete via the edit page.
    await adminPage.getByRole('button', { name: 'Edit' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'Edit Budget' }),
    ).toBeVisible()
    await adminPage.getByRole('button', { name: 'Delete' }).click()

    // Confirm the custom React confirmation dialog.
    const confirmDialog = adminPage.getByTestId('confirm-dialog')
    await expect(confirmDialog).toBeVisible()
    await confirmDialog.getByRole('button', { name: 'Delete' }).click()
    await expect(confirmDialog).not.toBeVisible()

    // Dashboard no longer lists the deleted budget limit.
    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible({ timeout: 10_000 })

    // Verify the project budget was removed: the project page shows no editable budget.
    await adminPage.goto(`/budgets/projects/${project.id}`)
    await expect(
      adminPage.getByRole('heading', { name: `${project.name} budgets` }),
    ).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByRole('button', { name: 'Edit' })).toHaveCount(0)

    // Cleanup: drop the project.
    await api.request('DELETE', `/projects/${project.id}`).catch(() => {
      // Ignore failures; projects may not hard-delete in Community.
    })
  })
})
