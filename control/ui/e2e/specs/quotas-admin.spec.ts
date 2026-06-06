import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Phase 8d &mdash; quota admin surface.
 *
 * Verifies the CRUD page lists quotas, can create one against a real
 * scope id (a project created via API), shows it in the table, and
 * deletes it. Arrange via API; assert via UI.
 */
test.describe('quotas admin', () => {
  test('admin can create, list, and delete a project quota', async ({
    adminPage,
    api,
  }) => {
    // Arrange: authenticated API client + a scope to attach the quota
    // to. We use a project here because PROJECT scope has the most
    // interesting validation path (the child-tightens-only walk).
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>(
      'POST',
      '/projects',
      {
        name: `quota-e2e-${Date.now()}`,
        description: 'Phase 8d quota admin spec',
      },
    )

    await adminPage.goto('/admin/quotas')

    await expect(
      adminPage.getByRole('heading', { name: 'Quotas' }),
    ).toBeVisible()

    // Open create dialog.
    await adminPage.getByRole('button', { name: 'New Quota' }).click()
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: 'New Quota' }),
    ).toBeVisible()

    // Fill in the form. PROJECT/TOKENS/DAILY is the most common shape.
    await adminPage.getByLabel('Scope ID').fill(project.id)
    await adminPage.getByLabel('Limit').fill('250000')
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // Row visible in the table with our scope id.
    const row = adminPage.getByRole('row', { name: new RegExp(project.id) })
    await expect(row).toBeVisible({ timeout: 10_000 })
    await expect(row.getByText('250,000')).toBeVisible()

    // Delete it &mdash; auto-accept the confirm() popup so the test
    // doesn't hang on the native dialog.
    adminPage.once('dialog', (d) => d.accept())
    await row.getByRole('button', { name: 'Delete' }).click()

    await expect(row).toHaveCount(0, { timeout: 10_000 })

    // Cleanup: drop the project so the next run starts clean.
    await api.request('DELETE', `/projects/${project.id}`).catch(() => {
      // Projects may not support hard delete in Community; ignore.
    })
  })
})
