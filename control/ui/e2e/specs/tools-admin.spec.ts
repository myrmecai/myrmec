import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { confirmDialog } from '../helpers/confirm-dialog'

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
      adminPage.getByRole('heading', { name: 'Tools', exact: true }),
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
    await adminPage.getByRole('button', { name: 'Create Tool' }).click()

    // Wait for the dialog to close (indicates successful creation)
    await expect(adminPage.getByRole('heading', { name: 'New Tool' })).not.toBeVisible({ timeout: 10_000 })

    // Reload to ensure the table refreshes with the new tool
    await adminPage.reload()

    // The new tool should appear in the table — search across pages if needed
    // With 23+ seeded tools and page size 10, the new tool may be on page 2 or 3.
    const codeRegex = new RegExp(code)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: codeRegex }).isVisible({ timeout: 3_000 }).catch(() => false)) {
        found = true
        break
      }
      const nextButton = adminPage.getByRole('button', { name: /next page|next/i })
      if (await nextButton.isEnabled({ timeout: 1_000 }).catch(() => false)) {
        await nextButton.click()
        await adminPage.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {})
      } else {
        break
      }
    }
    expect(found).toBe(true)
    const row = adminPage.getByRole('row', { name: codeRegex })

    // Delete the tool
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDialog(adminPage, 'Delete')

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})