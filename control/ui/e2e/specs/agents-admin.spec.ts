import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { confirmDialog } from '../helpers/confirm-dialog'

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
    await adminPage.goto('/platform/ai-infra/agent-hosts')

    await expect(
      adminPage.getByRole('heading', { name: 'Agent Hosts', exact: true }),
    ).toBeVisible()

    await expect(
      adminPage.getByText('All Agent Hosts', { exact: true }),
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

    await adminPage.goto('/platform/ai-infra/agent-hosts')

    // Open the create dialog
    await adminPage.getByRole('button', { name: 'New Agent Host' }).click()
    await expect(
      adminPage.getByRole('heading', { name: 'New Agent Host' }),
    ).toBeVisible()

    // Fill the form
    await adminPage.getByLabel('Name').fill(agentName)
    await adminPage.getByLabel('Description').fill('E2E test agent')

    // Select the agent profile (native <select> dropdown)
    const dialog = adminPage.getByRole('dialog')
    await dialog.getByLabel('Profile').selectOption({ label: profileName })

    // Submit
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // Wait for the dialog to close
    await expect(adminPage.getByRole('heading', { name: 'New Agent Host' })).not.toBeVisible({ timeout: 10_000 })

    // Reload to ensure the table refreshes with the new agent
    await adminPage.reload()

    // The new agent should appear in the table — search across pages if needed
    const nameRegex = new RegExp(agentName)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: nameRegex }).isVisible({ timeout: 3_000 }).catch(() => false)) {
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
    const row = adminPage.getByRole('row', { name: nameRegex })

    // Delete the agent
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDialog(adminPage, 'Delete')

    await expect(row).toHaveCount(0, { timeout: 10_000 })

    // Cleanup: delete the profile via API
    await api.request('DELETE', `/admin/agent-profiles/${profile.id}`)
  })
})