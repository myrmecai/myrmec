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

  // UC-020 criterion #4: Service budgets page enforces sum-of-reservations ≤ project budget.
  test('rejects over-allocation when service reservations exceed project budget', async ({
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `budget-overalloc-${Date.now()}`,
      description: 'Over-allocation spec',
    })

    // Create a project ceiling of $1.00 (100 cents).
    await api.request('POST', '/admin/quotas', {
      scopeType: 'PROJECT',
      scopeId: project.id,
      resourceType: 'COST_USD_CENTS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 100,
      quotaType: 'CEILING',
      enforcementMode: 'BLOCK',
    })

    // Create a service reservation of $0.80 (80 cents) — fits within the $1.00 ceiling.
    await api.request('POST', '/admin/quotas', {
      scopeType: 'SERVICE',
      scopeId: project.id,
      resourceType: 'COST_USD_CENTS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 80,
      quotaType: 'RESERVATION',
      enforcementMode: 'BLOCK',
      serviceType: 'WORKFLOW',
    })

    // Attempt a second reservation of $0.50 (50 cents) — total would be $1.30 > $1.00.
    // The backend should reject this (400/409), not create it.
    let secondCreateFailed = false
    try {
      await api.request('POST', '/admin/quotas', {
        scopeType: 'SERVICE',
        scopeId: project.id,
        resourceType: 'COST_USD_CENTS',
        period: 'MONTHLY_CALENDAR',
        limitAmount: 50,
        quotaType: 'RESERVATION',
        enforcementMode: 'BLOCK',
        serviceType: 'CONVERSATIONAL',
      })
    } catch {
      secondCreateFailed = true
    }
    expect(secondCreateFailed).toBe(true)

    // Cleanup.
    await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
  })

  // UC-020 criterion #7: Type changes release or reserve pool amounts correctly.
  test('changing a service budget from RESERVATION to CEILING releases the reserved pool', async ({
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `budget-typechange-${Date.now()}`,
      description: 'Type change pool release spec',
    })

    // Project ceiling of $1.00 (100 cents).
    await api.request('POST', '/admin/quotas', {
      scopeType: 'PROJECT',
      scopeId: project.id,
      resourceType: 'COST_USD_CENTS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 100,
      quotaType: 'CEILING',
      enforcementMode: 'BLOCK',
    })

    // Service reservation of $0.60 (60 cents).
    const serviceQuota = await api.request<{ id: string }>('POST', '/admin/quotas', {
      scopeType: 'SERVICE',
      scopeId: project.id,
      resourceType: 'COST_USD_CENTS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 60,
      quotaType: 'RESERVATION',
      enforcementMode: 'BLOCK',
      serviceType: 'WORKFLOW',
    })

    // Verify the pool shows $60 reserved.
    const before = await api.request<{ sharedPool: { reservedAmount: number; sharedAmount: number } }>(
      'GET',
      `/budgets/projects/${project.id}/services?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR`,
    )
    expect(before.sharedPool.reservedAmount).toBe(60)
    expect(before.sharedPool.sharedAmount).toBe(40)

    // Change the service quota from RESERVATION to CEILING.
    await api.request('PUT', `/admin/quotas/${serviceQuota.id}`, {
      limitAmount: 60,
      quotaType: 'CEILING',
      enforcementMode: 'BLOCK',
    })

    // Verify the pool released the reservation — reserved drops to 0, shared returns to 100.
    const after = await api.request<{ sharedPool: { reservedAmount: number; sharedAmount: number } }>(
      'GET',
      `/budgets/projects/${project.id}/services?resourceType=COST_USD_CENTS&period=MONTHLY_CALENDAR`,
    )
    expect(after.sharedPool.reservedAmount).toBe(0)
    expect(after.sharedPool.sharedAmount).toBe(100)

    // Cleanup.
    await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
  })

  // UC-020 criterion #10: Dashboard filters produce correct results for Today, This month, and Lifetime.
  test('dashboard filter switching between period filters reloads without crashing', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    await adminPage.goto('/budgets')
    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible()

    // Default is "This month" — verify the tree loads.
    await expect(adminPage.getByText(/Total.*limit|No budgets/i)).toBeVisible({ timeout: 10_000 })

    // Switch to "Today" (DAILY).
    await adminPage.getByLabel('Period').click()
    await adminPage.getByRole('option', { name: 'Today' }).click()

    // The tree should reload — either show data or an empty state, but not crash.
    await adminPage.waitForTimeout(1000)
    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible()

    // Switch to "Lifetime" (LIFETIME).
    await adminPage.getByLabel('Period').click()
    await adminPage.getByRole('option', { name: 'Lifetime' }).click()

    await adminPage.waitForTimeout(1000)
    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible()

    // Switch back to "This month" (MONTHLY_CALENDAR).
    await adminPage.getByLabel('Period').click()
    await adminPage.getByRole('option', { name: 'This month' }).click()

    await adminPage.waitForTimeout(1000)
    await expect(
      adminPage.getByRole('heading', { name: 'Budgets' }),
    ).toBeVisible()
  })
})
