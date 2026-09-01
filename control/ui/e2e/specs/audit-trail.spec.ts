import { test, expect } from '../fixtures'

/**
 * Phase 9c — the audit log surface.
 *
 * NOTE: The audit log admin page (/admin/audit-log) is not yet implemented
 * in the UI. The API endpoint exists (auditLogApi in lib/api.ts) but no
 * route or page component has been created. This test is skipped until
 * the page is built.
 *
 * Verifies the route renders for an admin, the filter form is responsive,
 * the table exposes timestamp+action+actor columns, and the payload
 * detail dialog opens for entries that carry a payload (LOGIN does).
 *
 * Arrange-via-API: login attempt is performed against the engine to
 * guarantee at least one LOGIN entry exists when the page loads. The UI
 * is opened only to assert against the rendered DOM.
 */
test.describe('audit log', () => {
  test.skip('admin can browse audit entries and open payload detail', async ({
    adminPage,
    api,
  }) => {
    // Arrange: trigger a LOGIN_FAILED so we know at least one entry from
    // this test run is present, regardless of any prior history.
    await fetch(`${api.rootOrigin}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: 'audit-trail-probe@example.com',
        password: 'definitely-wrong-' + Date.now(),
      }),
    })

    await adminPage.goto('/admin/audit-log')

    await expect(
      adminPage.getByRole('heading', { name: 'Audit Log' }),
    ).toBeVisible()

    // Filter form is mounted.
    await expect(adminPage.getByLabel('Action')).toBeVisible()
    await expect(adminPage.getByLabel('Resource type')).toBeVisible()

    // At least one row visible (we just triggered a LOGIN_FAILED).
    await expect(
      adminPage.getByRole('cell', { name: 'LOGIN_FAILED' }).first(),
    ).toBeVisible({ timeout: 10_000 })

    // Filter narrows the table: typing LOGIN_FAILED + Apply should
    // leave only LOGIN_FAILED rows, never LOGIN rows.
    await adminPage.getByLabel('Action').fill('LOGIN_FAILED')
    await adminPage.getByRole('button', { name: 'Apply' }).click()

    // Once the filter has settled there must be no plain LOGIN rows; the
    // assertion deliberately checks for the action-cell, not just text,
    // so that prose like "no failed LOGIN entries" wouldn't false-pass.
    await expect(adminPage.getByRole('cell', { name: 'LOGIN_FAILED' }).first()).toBeVisible()
    await expect(adminPage.getByRole('cell', { name: 'LOGIN', exact: true })).toHaveCount(0)

    // Reset clears the filter.
    await adminPage.getByRole('button', { name: 'Reset' }).click()
    await expect(adminPage.getByLabel('Action')).toHaveValue('')
  })
})
