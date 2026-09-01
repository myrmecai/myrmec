import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { confirmDialog } from '../helpers/confirm-dialog'

/**
 * Models admin — list, create, edit, delete.
 *
 * Verifies the models table renders seeded models, a new model can be
 * created via the dialog form, and a non-seeded model can be deleted.
 * System-seeded models (github-gpt-4o) should not be deletable.
 */
test.describe('models admin', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test('models list renders seeded models', async ({ adminPage }) => {
    await adminPage.goto('/platform/ai-infra/models')

    await expect(
      adminPage.getByRole('heading', { name: 'Models', exact: true }),
    ).toBeVisible()

    // The seeded model should be present
    await expect(
      adminPage.getByText('github-gpt-4o').first(),
    ).toBeVisible()
  })

  test('admin can create and delete a custom model', async ({ adminPage }) => {
    const code = `e2e-model-${Date.now().toString(36)}`

    await adminPage.goto('/platform/ai-infra/models')

    // Open the create dialog
    await adminPage.getByRole('button', { name: 'New Model' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'New Model' }),
    ).toBeVisible()

    // Fill the form — Cloud deployment, use the first available provider
    await adminPage.getByLabel('Model Code').fill(code)
    await adminPage.getByLabel('Display Name').fill('E2E Test Model')
    await adminPage.getByLabel('Model ID').fill('test-model-id')
    // Select the first available provider (use the select element directly)
    await adminPage.locator('select#provider').first().selectOption({ index: 1 })

    // Submit
    const [response] = await Promise.all([
      adminPage.waitForResponse((r) => r.url().includes('/admin/models') && r.request().method() === 'POST'),
      adminPage.getByRole('button', { name: 'Create Model' }).click(),
    ])
    console.log(`[models-admin] Create response: ${response.status()} ${response.statusText()}`)
    console.log(`[models-admin] Create body: ${await response.text()}`)

    // Wait for the dialog to close
    await expect(adminPage.getByRole('heading', { name: 'New Model' })).not.toBeVisible({ timeout: 10_000 })

    // Reload to ensure the table refreshes
    await adminPage.reload()
    await adminPage.waitForLoadState('networkidle', { timeout: 10_000 })

    // The new model should appear in the table — search across pages if needed
    const codeRegex = new RegExp(code)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: codeRegex }).isVisible({ timeout: 5_000 }).catch(() => false)) {
        found = true
        break
      }
      const nextButton = adminPage.getByRole('button', { name: /Go to next page/i })
      if (await nextButton.isEnabled({ timeout: 1_000 }).catch(() => false)) {
        await nextButton.click()
        await adminPage.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {})
      } else {
        break
      }
    }
    expect(found).toBe(true)
    const row = adminPage.getByRole('row', { name: codeRegex })

    // Delete the model
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDialog(adminPage, 'Delete')

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})