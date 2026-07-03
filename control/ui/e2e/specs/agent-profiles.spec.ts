import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Agent Profiles admin — list, create, delete.
 *
 * Verifies the agent profiles table renders, a new profile can be
 * created via the dialog form, and a profile can be deleted.
 */
test.describe('agent profiles admin', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test('agent profiles list renders with heading and table', async ({
    adminPage,
  }) => {
    await adminPage.goto('/platform/ai-infra/agent-profiles')

    await expect(
      adminPage.getByRole('heading', { name: 'Agent Profiles' }),
    ).toBeVisible()

    await expect(
      adminPage.getByText('All Profiles', { exact: true }),
    ).toBeVisible()
  })

  test('admin can create and delete an agent profile', async ({
    adminPage,
  }) => {
    const name = `E2E Profile ${Date.now().toString(36)}`

    await adminPage.goto('/platform/ai-infra/agent-profiles')

    // Open the create dialog
    await adminPage.getByRole('button', { name: 'New Profile' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'Create Agent Profile' }),
    ).toBeVisible()

    // Fill the form
    await adminPage.getByLabel('Name *').fill(name)
    await adminPage.getByLabel('Description').fill('E2E test agent profile')

    // Submit
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // The new profile should appear in the table
    const row = adminPage.getByRole('row', { name: new RegExp(name) })
    await expect(row).toBeVisible({ timeout: 10_000 })

    // Delete the profile
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })
})