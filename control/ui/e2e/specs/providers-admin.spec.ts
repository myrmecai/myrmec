import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { confirmDialog } from '../helpers/confirm-dialog'

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */

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
    await adminPage.goto('/platform/ai-infra/providers')

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
    await adminPage.getByRole('button', { name: 'Create' }).click()

    // Wait for the dialog to close
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: 'New Provider' }),
    ).not.toBeVisible({ timeout: 10_000 })

    // Reload to ensure the table refreshes
    await adminPage.reload()

    // The new provider should appear in the table — search across pages if needed
    const codeRegex = new RegExp(code)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: codeRegex }).isVisible({ timeout: 3_000 }).catch(() => false)) {
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
    await expect(row.getByText('custom')).toBeVisible()

    // Delete the custom provider.
    await row.getByRole('button', { name: 'Delete' }).click()
    await confirmDialog(adminPage, 'Delete')

    await expect(row).toHaveCount(0, { timeout: 10_000 })
  })

  /**
   * MPR-02 — System provider cannot be deleted.
   *
   * The delete button is disabled for system providers in the UI.
   * The API also returns 409 if the delete is attempted directly.
   */
  test('MPR-02: system provider delete button is disabled', async ({ adminPage, api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await adminPage.goto('/platform/ai-infra/providers')

    await expect(
      adminPage.getByRole('heading', { name: 'Model Providers' }),
    ).toBeVisible()

    // Find the first system provider row
    const systemCell = adminPage.getByRole('cell', { name: 'system' }).first()
    await expect(systemCell).toBeVisible()

    // Get the row containing the system cell
    const systemRow = adminPage.locator('tr', { has: adminPage.getByRole('cell', { name: 'system' }) }).first()

    // The delete button in that row should be disabled
    const deleteBtn = systemRow.getByRole('button', { name: /Delete/i })
    await expect(deleteBtn).toBeDisabled()

    // Also verify via API that a direct delete returns 409
    const providers = await api.request<{ code: string; isSystem: boolean }[]>('GET', '/admin/providers')
    const sysProvider = providers.find((p) => p.isSystem)
    expect(sysProvider).toBeDefined()

    const res = await api.rawRequest('DELETE', `/admin/providers/${sysProvider!.code}`)
    // System provider delete should be rejected — 400 (bad request), 409 (conflict), or 403 (forbidden)
    expect([400, 409, 403]).toContain(res.status)
  })

  /**
   * MPR-03 — Test connection button works for providers.
   *
   * Create a provider via API, link a connection config to it, set it ACTIVE,
   * then click the Test connection button and verify the result appears.
   */
  test('MPR-03: test connection button returns result', async ({ adminPage, api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    // Create a custom provider via API (INACTIVE by default for system, but admin-created is ACTIVE)
    const code = `e2e-test-conn-${Date.now().toString(36)}`
    await api.request('POST', '/admin/providers', {
      code,
      name: 'E2E Test Connection Provider',
      deploymentType: 'CLOUD',
      requiresAuth: false,
    })

    // Create a connection config via API and link it to the provider
    const configRes = await api.request<{ id: string }>('POST', '/admin/connection-configs', {
      name: `E2E Config ${Date.now().toString(36)}`,
      type: 'HTTP',
      scope: 'ORGANIZATION',
      config: {
        url: 'https://example.invalid/v1',
      },
    })
    await api.request('PUT', `/admin/providers/${code}`, {
      connectionConfigId: configRes.id,
    })

    await adminPage.goto('/platform/ai-infra/providers')

    // Wait for the table to load
    await expect(
      adminPage.getByRole('heading', { name: 'Model Providers' }),
    ).toBeVisible()

    // Wait for the table to refresh
    await adminPage.waitForLoadState('networkidle', { timeout: 10_000 })

    // Find the row for our provider — search across pages if needed
    const codeRegex = new RegExp(code)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: codeRegex }).first().isVisible({ timeout: 3_000 }).catch(() => false)) {
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

    const row = adminPage.getByRole('row', { name: codeRegex }).first()

    // Click the "Test connection" button (the Plug icon button)
    const testBtn = row.getByRole('button').nth(1) // 2nd button = Test connection
    await testBtn.click()

    // Wait for the test result toast to appear (bottom-right fixed position)
    await expect(
      adminPage.locator('.fixed').getByText(/Test Connection/i),
    ).toBeVisible({ timeout: 15_000 })

    // Clean up
    await api.rawRequest('DELETE', `/admin/providers/${code}`)
    await api.rawRequest('DELETE', `/admin/connection-configs/${configRes.id}`)
  })

  /**
   * MPR-04 — Inline connection config creation on provider create dialog.
   *
   * Click "New Provider", select "Create new connection config" in the
   * Connection Config picker, walk the wizard, and return with the new
   * config selected. The provider is then created and the config is linked.
   */
  test('MPR-04: create provider with inline connection config', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await adminPage.goto('/platform/ai-infra/providers')

    await expect(
      adminPage.getByRole('heading', { name: 'Model Providers' }),
    ).toBeVisible()

    // Create an inline secret to use as credential.
    const secretName = `e2e-secret-${Date.now().toString(36)}`
    const secretRes = await api.request<{ id: string; name: string }>('POST', '/admin/secrets', {
      name: secretName,
      type: 'API_KEY',
      payload: { type: 'API_KEY', key: 'sk-e2e-test' },
    })

    const code = `e2e-provider-inline-cc-${Date.now().toString(36)}`
    const configName = `E2E Inline Config ${Date.now().toString(36)}`

    await adminPage.getByRole('button', { name: 'New Provider' }).click()
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: 'New Provider' }),
    ).toBeVisible()

    await adminPage.getByLabel('Code').fill(code)
    await adminPage.getByLabel('Name').fill('E2E Inline CC Provider')

    // Open the connection config picker and choose inline creation.
    await adminPage.getByLabel('Connection Config').click()
    await adminPage
      .getByRole('option', { name: /Create new connection config/i })
      .click()

    // Step 1: choose type
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: /Create Connection Config/i }),
    ).toBeVisible()
    await adminPage.getByLabel('Connection Type').click()
    await adminPage.getByRole('option', { name: 'HTTP API' }).click()
    await adminPage.getByRole('button', { name: 'Continue' }).click()

    // Step 2: fill connection config details.
    await expect(
      adminPage
        .getByRole('dialog')
        .getByRole('heading', { name: /Create HTTP API Connection/i }),
    ).toBeVisible()
    await adminPage.locator('#cc-name').fill(configName)
    await adminPage.locator('#cc-url').fill('https://api.e2e-test.local/')
    await adminPage.locator('#cc-config-testEndpoint').fill('/health')
    await adminPage.locator('#cc-scope').click()
    await adminPage.getByRole('option', { name: 'System-wide' }).click()

    // Credential secret picker.
    await adminPage.locator('#cc-credential-secret-trigger').click()
    await adminPage
      .getByRole('option', { name: secretName })
      .click()

    await adminPage.getByRole('button', { name: 'Create & Select' }).click()

    // Wait for the wizard to close and the provider dialog to remain.
    await expect(
      adminPage
        .getByRole('dialog')
        .getByRole('heading', { name: /Create HTTP API Connection/i }),
    ).not.toBeVisible({ timeout: 10_000 })
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: 'New Provider' }),
    ).toBeVisible()

    // Submit the provider.
    await adminPage.getByRole('button', { name: 'Create' }).click()
    await expect(
      adminPage.getByRole('dialog').getByRole('heading', { name: 'New Provider' }),
    ).not.toBeVisible({ timeout: 10_000 })

    // Verify the provider is linked to a real connection config in the table.
    await adminPage.reload()
    const codeRegex = new RegExp(code)
    let found = false
    for (let page = 0; page < 5; page++) {
      if (await adminPage.getByRole('row', { name: codeRegex }).isVisible({ timeout: 3_000 }).catch(() => false)) {
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

    // The connection config column should not be empty.
    const row = adminPage.getByRole('row', { name: codeRegex })
    const ccCell = row.getByRole('cell').nth(3) // Connection Config column
    await expect(ccCell).not.toHaveText('—')

    // Cleanup
    const provider = await api.request<{ connectionConfigId: string | null }>(
      'GET',
      `/admin/providers/${code}`,
    )
    await api.rawRequest('DELETE', `/admin/providers/${code}`)
    if (provider.connectionConfigId) {
      await api.rawRequest('DELETE', `/admin/connection-configs/${provider.connectionConfigId}`)
    }
    await api.rawRequest('DELETE', `/admin/secrets/${secretRes.id}`)
  })
})
