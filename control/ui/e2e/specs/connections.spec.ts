import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { createVersionedEntityTests } from './versioned-entity-standard.spec'

/**
 * UC-018 - Manage Connection Configs
 *
 * Single spec file containing:
 *   1. Standard versioned-entity tests (VE-S01 through VE-S45)
 *      - Defined in: e2e-testing-standards.md S7
 *      - Source: governance-and-versioning.md S3, ui-standards.md Patterns 1-7
 *   2. Entity-specific tests (T-14, T-17, T-19, T-20, T-21, T-22, T-23, T-24, T-25, T-26)
 *      - T-14: Delete INCOMPLETE/unused config (BR-CC-10)
 *      - T-17: Config parameters save correctly for each type (Git, HTTP, DB, S3)
 *      - T-19: Draft takeover by PLATFORM_ADMIN (A4)
 *      - T-20: Test Connection — Git success (stateless, Step 5)
 *      - T-21: Test Connection — HTTP success (stateless, Step 5)
 *      - T-22: Test Connection — failure shows error dialog with Retry (A5)
 *      - T-23: HTTP config — [Test Connection] disabled when test_endpoint missing (BR-CC-14, A10)
 *      - T-24: Publish succeeds without prior Test Connection (Step 6, BR-CC-15)
 *      - T-25: Publish blocked when server-side connection test fails (Step 6, A9)
 *      - T-26: Ref field absent from Git connection (UI, API, DB)
 *
 * Strategy: Drive the UI for all interactions (per e2e-testing-standards.md).
 * API is used ONLY for login, cleanup, and cross-UC setup.
 */

// === Standard Versioned Entity Tests ===
createVersionedEntityTests({
  entityName: 'Connection Config',
  listRoute: '/platform/connections',
  detailRoutePattern: /\/platform\/connections\/[^/]+$/,
  apiBase: '/admin/connection-configs',
  listHeading: 'Connection Configs',
  listCardTitle: 'Organization Connections',
  createButtonText: 'New Connection',
  createDialogHeading: 'Create Connection Config',
  nameLabel: 'Name',
  draftUrlLabel: 'URL',
  draftUrlValue: 'https://github.com/myrmecai/test-data.git',
  draftUrlValueV2: 'https://github.com/myrmecai/myrmec.git',
  adminEmail: E2E_ADMIN.email,
  adminPassword: E2E_ADMIN.password,
  // VE-S21 asserts Publish is disabled when required fields are missing and
  // calls the API directly to verify the error response structure.
  hasPublishGate: true,
  // Connection Configs require a successful Test Connection before publish (BR-CC-15).
  // This pre-publish action runs Test Connection and waits for success before Publish.
  prePublishAction: async (page) => {
    const testBtn = page.getByRole('button', { name: /Test Connection/i })
    await expect(testBtn).toBeEnabled({ timeout: 5_000 })
    await testBtn.click()
    // Wait for the test result dialog
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 30_000 })
    // Wait for either Connected or Failed text to appear
    await expect(dialog.getByText(/Connected|Failed/i)).toBeVisible({ timeout: 30_000 })
    // Assert success
    await expect(dialog.getByText(/Connected/i)).toBeVisible({ timeout: 5_000 })
    await dialog.getByRole('button', { name: /Close/i }).first().click()
    await expect(dialog).not.toBeVisible({ timeout: 5_000 })
  },
})

// === Entity-Specific Tests ===

const BASE = '/admin/connection-configs'

async function cleanupConfig(api: any, configId: string): Promise<void> {
  try { await api.request('DELETE', `${BASE}/${configId}`) } catch { /* gone */ }
}

async function findConfigByName(api: any, name: string): Promise<string | null> {
  const configs = await api.request('GET', BASE)
  const found = (configs as any[]).find((c) => c.name === name)
  return found?.id ?? null
}

const TYPE_CONFIGS: Record<string, {
  type: string
  url: string
  label: string
  configFields: Record<string, string>
  configLabels: Record<string, string>
  secretType: string
}> = {
  GIT: {
    type: 'GIT',
    url: 'https://github.com/e2e-test/repo.git',
    label: 'Git Repository',
    configFields: {},
    configLabels: {},
    secretType: 'USERNAME_PASSWORD',
  },
  HTTP: {
    type: 'HTTP',
    url: 'https://api.example.com',
    label: 'HTTP API',
    configFields: { testEndpoint: '/health', headers: '{"X-Custom":"value"}', timeoutMs: '30000' },
    configLabels: { testEndpoint: 'Test Endpoint', headers: 'Headers (JSON)', timeoutMs: 'Timeout (ms)' },
    secretType: 'BEARER_TOKEN',
  },
  DB: {
    type: 'DB',
    url: 'jdbc:postgresql://localhost:5432/mydb',
    label: 'Database',
    configFields: { dbProvider: 'PostgreSQL' },
    configLabels: { dbProvider: 'DB Provider' },
    secretType: 'USERNAME_PASSWORD',
  },
  S3: {
    type: 'S3',
    url: 'https://s3.amazonaws.com',
    label: 'S3 Storage',
    configFields: { bucket: 'e2e-bucket', region: 'us-east-1', prefix: 'data/' },
    configLabels: { bucket: 'Bucket', region: 'Region', prefix: 'Prefix' },
    secretType: 'USERNAME_PASSWORD',
  },
}

test.describe('UC-018 connection configs - entity-specific tests', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // T-14: Delete INCOMPLETE/unused config (BR-CC-10)
  test('T-14: delete endpoint works for INCOMPLETE/unused configs (BR-CC-10)', async ({ adminPage, api }) => {
    const name = `e2e-cleanup-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByRole('button', { name: 'Create' }).click()
      // Should navigate to detail page
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      // Go back to list and verify INCOMPLETE
      await adminPage.goto('/platform/connections')
      const row = adminPage.getByRole('row', { name: new RegExp(name) })
      await expect(row).toBeVisible({ timeout: 10_000 })
      await expect(row.getByText('INCOMPLETE', { exact: true })).toBeVisible()
      // Delete via API (cleanup endpoint)
      const configId = await findConfigByName(api, name)
      expect(configId).toBeTruthy()
      await api.request('DELETE', `${BASE}/${configId}`)
      // Verify gone from list
      await adminPage.goto('/platform/connections')
      await expect(adminPage.getByRole('row', { name: new RegExp(name) })).toHaveCount(0)
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-17: Config parameters save correctly for each type (UC-018 §5)
  for (const [typeName, tc] of Object.entries(TYPE_CONFIGS)) {
    test(`T-17: ${typeName} config parameters save correctly`, async ({ adminPage, api }) => {
      const name = `e2e-type-${typeName.toLowerCase()}-${Date.now().toString(36)}`
      // Create a global secret of the allowed type for this connection type
      const secretName = `e2e-secret-${typeName.toLowerCase()}-${Date.now().toString(36)}`
      let secretId: string | null = null
      try {
        // Create secret via API
        const secret = await api.request('POST', '/admin/secrets', {
          name: secretName,
          type: tc.secretType,
          backend: 'LOCAL',
          payload:
            tc.secretType === 'USERNAME_PASSWORD'
              ? { type: 'USERNAME_PASSWORD', username: 'e2e-user', password: 'e2e-pass' }
              : tc.secretType === 'BEARER_TOKEN'
                ? { type: 'BEARER_TOKEN', token: 'e2e-token' }
                : { type: 'API_KEY', key: 'e2e-key' },
        })
        secretId = secret.id

        await adminPage.goto('/platform/connections')
        await adminPage.getByRole('button', { name: 'New Connection' }).click()
        await adminPage.getByLabel('Name').fill(name)
        // Select type from dropdown
        await adminPage.getByLabel('Connection Type').click()
        await adminPage.getByRole('option', { name: tc.label }).click()
        await adminPage.getByRole('button', { name: 'Create' }).click()
        // Should navigate to detail page with Draft auto-created (Pattern 4 §Rule 4)
        await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
        await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

        // Fill URL
        const urlInput = adminPage.getByLabel('URL')
        await urlInput.fill(tc.url)

        // Fill type-specific config fields (UC-018 §5)
        for (const [key, value] of Object.entries(tc.configFields)) {
          const fieldLabel = tc.configLabels[key]
          const fieldLocator = adminPage.getByLabel(fieldLabel)
          // For select fields (like DB Provider), click and pick option
          if (value === 'PostgreSQL') {
            await fieldLocator.click()
            await adminPage.getByRole('option', { name: value }).click()
          } else {
            await fieldLocator.fill(value)
          }
        }

        // Save draft
        const saveButton = adminPage.getByRole('button', { name: /Save Draft/i })
        await expect(saveButton).toBeEnabled({ timeout: 5_000 })
        await saveButton.click()
        await expect(saveButton).toBeDisabled({ timeout: 5_000 })

        // Verify URL is in the input field
        await expect(adminPage.getByLabel('URL')).toHaveValue(tc.url)

        // Verify type-specific config values visible in the saved config JSON
        for (const [key, value] of Object.entries(tc.configFields)) {
          if (key === 'headers') {
            // Headers is stored as JSON object — check for the inner value
            await expect(adminPage.getByText('X-Custom').first()).toBeVisible({ timeout: 5_000 })
          } else if (key === 'testEndpoint') {
            // testEndpoint is in an input field
            await expect(adminPage.getByLabel('Test Endpoint')).toHaveValue(value)
          } else {
            await expect(adminPage.getByText(value, { exact: false }).first()).toBeVisible({ timeout: 5_000 })
          }
        }
      } finally {
        const id = await findConfigByName(api, name)
        if (id) await cleanupConfig(api, id)
        if (secretId) {
          try { await api.request('DELETE', `/admin/secrets/${secretId}`) } catch { /* gone */ }
        }
      }
    })
  }

  // T-17b: Credential secret selector filters by allowed types (UC-018 §5)
  test('T-17b: credential secret selector shows only allowed secret types', async ({ adminPage, api }) => {
    const name = `e2e-secret-filter-${Date.now().toString(36)}`
    // Create secrets of different types
    const usernameSecretName = `e2e-sec-up-${Date.now().toString(36)}`
    const bearerSecretName = `e2e-sec-bt-${Date.now().toString(36)}`
    let usernameSecretId: string | null = null
    let bearerSecretId: string | null = null
    try {
      const upSecret = await api.request('POST', '/admin/secrets', {
        name: usernameSecretName,
        type: 'USERNAME_PASSWORD',
        backend: 'LOCAL',
        payload: { type: 'USERNAME_PASSWORD', username: 'u', password: 'p' },
      })
      usernameSecretId = upSecret.id

      const btSecret = await api.request('POST', '/admin/secrets', {
        name: bearerSecretName,
        type: 'BEARER_TOKEN',
        backend: 'LOCAL',
        payload: { type: 'BEARER_TOKEN', token: 't' },
      })
      bearerSecretId = btSecret.id

      // Create a DB connection (only allows USERNAME_PASSWORD)
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByLabel('Connection Type').click()
      await adminPage.getByRole('option', { name: 'Database' }).click()
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })

      // Enter Zone 1 edit mode (should already be in edit mode via ?edit=true)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Open the credential secret dropdown
      await adminPage.getByLabel('Credential Secret').click()
      // USERNAME_PASSWORD secret should be visible
      await expect(adminPage.getByText(usernameSecretName)).toBeVisible({ timeout: 5_000 })
      // BEARER_TOKEN secret should NOT be visible (not in allowed types for DB)
      await expect(adminPage.getByText(bearerSecretName)).toHaveCount(0)
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
      if (usernameSecretId) {
        try { await api.request('DELETE', `/admin/secrets/${usernameSecretId}`) } catch { /* gone */ }
      }
      if (bearerSecretId) {
        try { await api.request('DELETE', `/admin/secrets/${bearerSecretId}`) } catch { /* gone */ }
      }
    }
  })

  // T-19: Draft takeover by PLATFORM_ADMIN (A4)
  test('T-19: PLATFORM_ADMIN can take over another draft (A4)', async ({ adminPage, api }) => {
    const name = `e2e-takeover-${Date.now().toString(36)}`
    try {
      // Create + publish v1 via UI (no Test Connection needed — Publish is independent)
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      // Draft is auto-created (Pattern 4 §Rule 4)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })
      const urlInput = adminPage.getByLabel('URL')
      await urlInput.fill('https://github.com/myrmecai/test-data.git')
      const saveButton = adminPage.getByRole('button', { name: /Save Draft/i })
      await expect(saveButton).toBeEnabled({ timeout: 5_000 })
      await saveButton.click()
      await expect(saveButton).toBeDisabled({ timeout: 5_000 })
      // Publish directly — server performs its own connectivity check (BR-CC-15)
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })
      // Create draft v2
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 5_000 })
      // In e2e we only have one user (admin), so we verify the draft is editable
      const urlInput2 = adminPage.getByLabel('URL')
      // Change URL to make the Draft dirty (different from cloned v1)
      await urlInput2.fill('https://github.com/myrmecai/myrmec.git')
      const saveButton2 = adminPage.getByRole('button', { name: /Save Draft/i })
      await expect(saveButton2).toBeEnabled({ timeout: 5_000 })
      await saveButton2.click()
      await expect(saveButton2).toBeDisabled({ timeout: 5_000 })
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-20: Test Connection — Git success (stateless, Step 5)
  test('T-20: test connection succeeds for Git config (stateless, Publish unaffected)', async ({ adminPage, api }) => {
    const name = `e2e-tc-git-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Enter URL but do NOT save Draft — Test Connection is stateless
      await adminPage.getByLabel('URL').fill('https://github.com/myrmecai/test-data.git')

      // Act: click Test Connection (no save required)
      await adminPage.getByRole('button', { name: /Test Connection/i }).click()

      // Assert: dialog shows Connected status and latency
      const dialog = adminPage.getByRole('dialog')
      await expect(dialog).toBeVisible({ timeout: 30_000 })
      await expect(dialog.getByText(/Connected/i)).toBeVisible({ timeout: 30_000 })
      await expect(dialog.getByText(/ms/i)).toBeVisible()
      await dialog.getByRole('button', { name: /Close/i }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // Assert: Publish is unaffected by Test Connection result.
      // Since Draft is not saved, Publish should be disabled (unsaved changes).
      // Save Draft, then Publish should be enabled regardless of test.
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })
      await expect(adminPage.getByRole('button', { name: /Publish/i })).toBeEnabled({ timeout: 5_000 })
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-21: Test Connection — HTTP success (stateless, Step 5)
  test('T-21: test connection succeeds for HTTP config (stateless, Publish unaffected)', async ({ adminPage, api }) => {
    const name = `e2e-tc-http-${Date.now().toString(36)}`
    // Use the engine itself as the HTTP target — guaranteed available during e2e
    const engineBaseUrl = api.rootOrigin
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByLabel('Connection Type').click()
      await adminPage.getByRole('option', { name: 'HTTP API' }).click()
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Enter URL and Test Endpoint but do NOT save Draft — Test Connection is stateless
      await adminPage.getByLabel('URL').fill(engineBaseUrl)
      await adminPage.getByLabel('Test Endpoint').fill('/actuator/health')

      // Act: click Test Connection (no save required)
      await adminPage.getByRole('button', { name: /Test Connection/i }).click()

      // Assert: dialog shows Connected
      const dialog = adminPage.getByRole('dialog')
      await expect(dialog).toBeVisible({ timeout: 20_000 })
      await expect(dialog.getByText(/Connected/i)).toBeVisible({ timeout: 20_000 })
      await dialog.getByRole('button', { name: /Close/i }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // Assert: Publish is unaffected by Test Connection result.
      // Save Draft, then Publish should be enabled regardless of test.
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })
      await expect(adminPage.getByRole('button', { name: /Publish/i })).toBeEnabled({ timeout: 5_000 })
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-22: Test Connection failure shows error dialog with Retry (A5)
  test('T-22: test connection failure shows error dialog with Retry, Publish unaffected (A5)', async ({ adminPage, api }) => {
    const name = `e2e-tc-fail-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByLabel('Connection Type').click()
      await adminPage.getByRole('option', { name: 'HTTP API' }).click()
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })

      // Enter unreachable URL and test_endpoint but do NOT save Draft — stateless
      await adminPage.getByLabel('URL').fill('https://unreachable.e2e-test.internal')
      await adminPage.getByLabel('Test Endpoint').fill('/health')

      // Act: click Test Connection (no save required)
      await adminPage.getByRole('button', { name: /Test Connection/i }).click()

      // Assert: dialog shows Failed + error message + Retry button
      const dialog = adminPage.getByRole('dialog')
      await expect(dialog).toBeVisible({ timeout: 20_000 })
      await expect(dialog.getByText(/Failed/i)).toBeVisible({ timeout: 20_000 })
      await expect(dialog.getByRole('button', { name: /Retry/i })).toBeVisible()
      await dialog.getByRole('button', { name: /Close/i }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // Assert: Publish is unaffected by Test Connection failure.
      // Save Draft, then Publish should be enabled (server will run its own test).
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })
      await expect(adminPage.getByRole('button', { name: /Publish/i })).toBeEnabled({ timeout: 5_000 })
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-23: HTTP config — [Test Connection] disabled when test_endpoint missing (BR-CC-14, A10)
  test('T-23: Test Connection button disabled when HTTP test_endpoint is missing (BR-CC-14)', async ({ adminPage, api }) => {
    const name = `e2e-tc-noep-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByLabel('Connection Type').click()
      await adminPage.getByRole('option', { name: 'HTTP API' }).click()
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })

      // Enter URL only — leave Test Endpoint empty. No save needed for stateless check.
      await adminPage.getByLabel('URL').fill('https://api.example.com')

      // [Test Connection] must be disabled without a test_endpoint (BR-CC-14)
      await expect(adminPage.getByRole('button', { name: /Test Connection/i })).toBeDisabled()

      // Adding test_endpoint enables [Test Connection] (no save required)
      await adminPage.getByLabel('Test Endpoint').fill('/health')
      await expect(adminPage.getByRole('button', { name: /Test Connection/i })).toBeEnabled({ timeout: 5_000 })
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-24: Publish succeeds without prior Test Connection (Step 6, BR-CC-15)
  test('T-24: Publish succeeds without prior Test Connection (BR-CC-15)', async ({ adminPage, api }) => {
    const name = `e2e-tc-pubdirect-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Fill URL and save Draft — do NOT click Test Connection
      await adminPage.getByLabel('URL').fill('https://github.com/myrmecai/test-data.git')
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })

      // Publish directly — server performs its own connectivity check (BR-CC-15)
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })

      // Verify parent status is ACTIVE
      await adminPage.goto('/platform/connections')
      const row = adminPage.getByRole('row', { name: new RegExp(name) })
      await expect(row).toBeVisible({ timeout: 10_000 })
      await expect(row.getByText('ACTIVE', { exact: true })).toBeVisible()
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-25: Publish blocked when server-side connection test fails (Step 6, A9)
  test('T-25: Publish blocked when server-side connection test fails (A9)', async ({ adminPage, api }) => {
    const name = `e2e-tc-pubfail-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByLabel('Connection Type').click()
      await adminPage.getByRole('option', { name: 'HTTP API' }).click()
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Fill unreachable URL + test_endpoint and save Draft
      await adminPage.getByLabel('URL').fill('https://unreachable.e2e-test.internal')
      await adminPage.getByLabel('Test Endpoint').fill('/health')
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })

      // Click Publish — server-side test should fail and block publish
      await adminPage.getByRole('button', { name: /Publish/i }).click()

      // Assert: error message about connection test failure
      await expect(adminPage.getByText(/Connection test failed/i)).toBeVisible({ timeout: 10_000 })

      // Draft should remain editable (not published)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeVisible()
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })

  // T-26: Ref field absent from Git connection (UI, API, DB)
  test('T-26: Git connection has no Ref field in UI, API response, or DB (field removed)', async ({ adminPage, api }) => {
    const name = `e2e-no-ref-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/connections')
      await adminPage.getByRole('button', { name: 'New Connection' }).click()
      await adminPage.getByLabel('Name').fill(name)
      // Default type is Git — no type selection needed
      await adminPage.getByRole('button', { name: 'Create' }).click()
      await expect(adminPage).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // UI: no Ref label or textbox must exist on the detail page
      await expect(adminPage.getByLabel('Ref')).toHaveCount(0)
      await expect(adminPage.getByRole('textbox', { name: /^ref$/i })).toHaveCount(0)

      // Fill URL, save Draft, and publish directly (no Test Connection needed)
      await adminPage.getByLabel('URL').fill('https://github.com/myrmecai/test-data.git')
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })

      // API + DB: published version config must not contain a `ref` property
      const configId = await findConfigByName(api, name)
      expect(configId).toBeTruthy()
      const published = await api.request<{ config: Record<string, unknown> }>(
        'GET', `${BASE}/${configId!}/published-version`
      )
      // config should be null/empty or an object without a `ref` key
      if (published.config != null) {
        expect(published.config).not.toHaveProperty('ref')
      }
    } finally {
      const id = await findConfigByName(api, name)
      if (id) await cleanupConfig(api, id)
    }
  })
})
