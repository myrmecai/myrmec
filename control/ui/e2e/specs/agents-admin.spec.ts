import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Agents admin — list and create.
 *
 * Verifies the agents table renders with the correct columns,
 * and a new agent can be created via the dialog form. Agent creation
 * requires an agent profile and optionally a project, both arranged
 * via API beforehand. Deletion is also tested.
 *
 * Note: This spec does NOT start an agent process — it only tests the
 * admin UI surface for managing agent definitions.
 */
test.describe('agents admin', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  test('agents list renders with heading and table columns', async ({
    adminPage,
  }) => {
    await adminPage.goto('/platform/ai-infra/agents')

    await expect(
      adminPage.getByRole('heading', { name: 'Agents' }),
    ).toBeVisible()

    await expect(
      adminPage.getByText('All Agents', { exact: true }),
    ).toBeVisible()

    // Column headers
    await expect(
      adminPage.getByRole('columnheader', { name: 'Name' }),
    ).toBeVisible()
    await expect(
      adminPage.getByRole('columnheader', { name: 'Profile' }),
    ).toBeVisible()
    await expect(
      adminPage.getByRole('columnheader', { name: 'Status' }),
    ).toBeVisible()
  })

  test('admin can create and delete an agent', async ({ adminPage, api }) => {
    // Arrange: create an agent profile via API (required for agent creation)
    const profileName = `E2E Agent Profile ${Date.now().toString(36)}`
    const profile = await api.request('POST', '/admin/agent-profiles', {
      name: profileName,
      description: 'E2E test profile for agent creation',
      capabilities: [],
      toolCodes: [],
    })

    const agentName = `E2E Agent ${Date.now().toString(36)}`

    await adminPage.goto('/platform/ai-infra/agents')

    // Open the create dialog
    await adminPage.getByRole('button', { name: 'New Agent' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'New Agent' }),
    ).toBeVisible()

    // Fill the form
    await adminPage.getByLabel('Name *').fill(agentName)
    await adminPage.getByLabel('Description').fill('E2E test agent')

    // Select the agent profile (dropdown)
    await adminPage.getByLabel('Agent Profile *').click()
    await adminPage.getByText(profileName).click()

    // Submit
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // The new agent should appear in the table
    const row = adminPage.getByRole('row', { name: new RegExp(agentName) })
    await expect(row).toBeVisible({ timeout: 10_000 })

    // Delete the agent
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })

    // Cleanup: delete the profile via API
    await api.request('DELETE', `/admin/agent-profiles/${profile.id}`)
  })
})