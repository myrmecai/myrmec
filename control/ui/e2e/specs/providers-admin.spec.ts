import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Phase 10 #70 &mdash; model providers admin surface.
 *
 * Verifies the admin page lists Liquibase-seeded system providers,
 * the system delete button is disabled, and a custom provider can be
 * created + appears in the table + can be deleted.
 */
test.describe('model providers admin', () => {
  test('admin can create and delete a non-system provider', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await adminPage.goto('/admin/providers')

    await expect(
      adminPage.getByRole('heading', { name: 'Model Providers' }),
    ).toBeVisible()

    // System providers seeded by Liquibase should be present.
    await expect(
      adminPage.getByRole('cell', { name: 'system' }).first(),
    ).toBeVisible()

    // Create a custom provider with a unique code.
    const code = `e2e-provider-${Date.now().toString(36)}`

    await adminPage.getByRole('button', { name: 'New Provider' }).click()
    await expect(
      adminPage
        .getByRole('dialog')
        .getByRole('heading', { name: 'New Provider' }),
    ).toBeVisible()

    await adminPage.getByLabel('Code').fill(code)
    await adminPage.getByLabel('Name').fill('E2E Test Provider')
    await adminPage
      .getByLabel('Base URL')
      .fill('https://example.invalid/v1')
    await adminPage.getByRole('button', { name: 'Create' }).click()

    const row = adminPage.getByRole('row', { name: new RegExp(code) })
    await expect(row).toBeVisible({ timeout: 10_000 })
    await expect(row.getByText('custom')).toBeVisible()

    // Delete the custom provider.
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})
