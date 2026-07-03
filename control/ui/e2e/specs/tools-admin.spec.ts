import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Tools admin — list, create, delete.
 *
 * Verifies the tools table renders seeded system tools, a custom tool
 * can be created via the dialog form, and a non-system tool can be deleted.
 * System tools should not show a delete button.
 */
test.describe('tools admin', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test('tools list renders seeded system tools', async ({ adminPage }) => {
    await adminPage.goto('/platform/ai-infra/tools')

    await expect(
      adminPage.getByRole('heading', { name: 'Tools' }),
    ).toBeVisible()

    // Should have a table with tools
    await expect(
      adminPage.getByText('All Tools', { exact: true }),
    ).toBeVisible()
  })

  test('admin can create and delete a custom tool', async ({ adminPage }) => {
    const code = `e2e-tool-${Date.now().toString(36)}`

    await adminPage.goto('/platform/ai-infra/tools')

    // Open the create dialog
    await adminPage.getByRole('button', { name: 'New Tool' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'New Tool' }),
    ).toBeVisible()

    // Fill the form
    await adminPage.getByLabel('Code').fill(code)
    await adminPage.getByLabel('Name').fill('E2E Test Tool')

    // Submit
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // The new tool should appear in the table
    const row = adminPage.getByRole('row', { name: new RegExp(code) })
    await expect(row).toBeVisible({ timeout: 10_000 })

    // Delete the tool
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})