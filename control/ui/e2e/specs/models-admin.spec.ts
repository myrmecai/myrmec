import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

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
      adminPage.getByRole('heading', { name: 'Models' }),
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

    // Submit
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // The new model should appear in the table
    const row = adminPage.getByRole('row', { name: new RegExp(code) })
    await expect(row).toBeVisible({ timeout: 10_000 })

    // Delete the model
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})