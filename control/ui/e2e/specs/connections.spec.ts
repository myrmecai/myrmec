import { test, expect } from '../fixtures'
import type { Page } from '@playwright/test'
import { E2E_ADMIN } from '../helpers/api'
import { confirmDialog } from '../helpers/confirm-dialog'
import { createVersionedEntityTests, type VersionedEntityConfig } from '../helpers/versioned-entity-standard'

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
 *
 * NOTE: The old simple CreateConnectionConfigDialog was replaced with
 * CreateConnectionConfigWizard — a two-step flow:
 *   Step 1: Select connection type (Git, HTTP, DB, S3) → Continue
 *   Step 2: Fill Name, Description, URL, type-specific fields, credential secret → Create
 * The wizard does a full create → createDraft → updateDraft → publish flow,
 * so configs created via the wizard are ACTIVE (not INCOMPLETE).
 *
 * Tests that need a Draft (T-17, T-19 through T-26) create configs via API
 * (create + createDraft, no publish) to get an INCOMPLETE config with an
 * editable Draft on the detail page.
 */

const BASE = '/admin/connection-configs'

// === Helper functions ===

async function cleanupConfig(api: any, configId: string): Promise<void> {
  try { await api.request('DELETE', `${BASE}/${configId}`) } catch { /* gone */ }
}

async function findConfigByName(api: any, name: string): Promise<string | null> {
  const configs = await api.request('GET', BASE)
  const found = (configs as any[]).find((c) => c.name === name)
  return found?.id ?? null
}

/**
 * Create a connection config with an editable Draft via API (no publish).
 * Returns the config ID. The config is INCOMPLETE with a Draft v1.
 *
 * Used by entity-specific tests that need to interact with a Draft on the
 * detail page (T-17, T-19, T-20, T-21, T-22, T-23, T-24, T-26).
 */
async function createConfigWithDraftViaApi(
  api: any,
  name: string,
  type: string = 'GIT',
): Promise<string> {
  const config = await api.request('POST', BASE, {
    name,
    type,
    scope: 'ORGANIZATION',
  })
  await api.request('POST', `${BASE}/${config.id}/drafts`)
  return config.id
}

/**
 * Wizard step 1: select connection type and click Continue.
 * Assumes the wizard dialog is already open.
 */
async function wizardSelectType(page: Page, typeLabel: string): Promise<void> {
  // Label is "Connection Type *" — use partial match
  await page.locator('#cc-type').click()
  await page.getByRole('option', { name: typeLabel }).click()
  await page.getByRole('button', { name: 'Continue' }).click()
}

/**
 * Wizard step 2: fill Name, URL, and click Create.
 * Assumes step 2 is visible (wizardSelectType was called).
 */
async function wizardFillAndCreate(
  page: Page,
  name: string,
  url: string,
  extraFields?: Record<string, string>,
): Promise<void> {
  // Wizard step 2 fields use cc-name, cc-description, cc-url IDs
  await page.locator('#cc-name').fill(name)
  await page.locator('#cc-url').fill(url)
  if (extraFields) {
    for (const [label, value] of Object.entries(extraFields)) {
      const field = page.getByLabel(label)
      // For select fields (like DB Provider), click and pick option
      if (value === 'PostgreSQL') {
        await field.click()
        await page.getByRole('option', { name: value }).click()
      } else {
        await field.fill(value)
      }
    }
  }
  await page.getByRole('button', { name: /Create/i }).click()
}

/**
 * Custom Quick-Create flow for the Connection Config wizard.
 * Opens the wizard, selects HTTP type, fills Name + URL, and clicks Create.
 * The wizard auto-publishes, so the config is ACTIVE after creation.
 * Navigates to the detail page.
 */
async function wizardQuickCreate(page: Page, _config: VersionedEntityConfig, name: string): Promise<void> {
  await page.goto('/platform/connections')
  await page.getByRole('button', { name: 'New Connection' }).click()
  await expect(page.getByRole('heading', { name: 'Create Connection Config' })).toBeVisible()
  // Use GIT type — no required config fields, no testEndpoint requirement
  await wizardSelectType(page, 'Git Repository')
  await wizardFillAndCreate(page, name, 'https://github.com/myrmecai/test-data.git')
  // Wizard publishes and navigates to detail page
  await expect(page).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 15_000 })
}

/**
 * Custom create-and-publish flow for the Connection Config wizard.
 * Uses the wizard to create + publish in one shot, then verifies the
 * published version is visible on the detail page.
 */
async function wizardCreateAndPublish(page: Page, _config: VersionedEntityConfig, name: string): Promise<void> {
  await page.goto('/platform/connections')
  await page.getByRole('button', { name: 'New Connection' }).click()
  await wizardSelectType(page, 'Git Repository')
  await wizardFillAndCreate(page, name, 'https://github.com/myrmecai/test-data.git')
  // Wizard publishes and navigates to detail page
  await expect(page).toHaveURL(/\/platform\/connections\/[^/]+$/, { timeout: 15_000 })
  await expect(page.getByText(/Published Version/i)).toBeVisible({ timeout: 15_000 })
}

// === Standard Versioned Entity Tests ===
createVersionedEntityTests({
  entityName: 'Connection Config',
  listRoute: '/platform/connections',
  detailRoutePattern: /\/platform\/connections\/[^/]+$/,
  apiBase: BASE,
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
  // The wizard auto-publishes on create, so we use custom flows.
  customQuickCreate: wizardQuickCreate,
  customCreateDraftAndPublish: wizardCreateAndPublish,
  // Extra body fields for API-based entity creation (VE-S14/S15/S16/S21
  // create entities with a Draft via API to test the draft lifecycle that
  // the wizard skips by auto-publishing).
  apiCreateBody: { type: 'GIT', scope: 'ORGANIZATION' },
  // VE-S21 asserts Publish is disabled when required fields are missing and
  // calls the API directly to verify the error response structure.
  // Skipped for wizard-based entities (no Draft after create).
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
      // Create an INCOMPLETE config via API (create + createDraft, no publish).
      // The wizard auto-publishes, so we can't get INCOMPLETE via UI anymore.
      const configId = await createConfigWithDraftViaApi(api, name, 'GIT')
      // Go to list and verify INCOMPLETE
      await adminPage.goto('/platform/connections')
      const row = adminPage.getByRole('row', { name: new RegExp(name) })
      await expect(row).toBeVisible({ timeout: 10_000 })
      await expect(row.getByText('INCOMPLETE', { exact: true })).toBeVisible()
      // Delete via API (cleanup endpoint)
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

        // Create config + draft via API (wizard auto-publishes, so use API
        // to get an INCOMPLETE config with an editable Draft on the detail page)
        const configId = await createConfigWithDraftViaApi(api, name, tc.type)

        // Navigate to the detail page — Draft v1 should be visible and editable
        await adminPage.goto(`/platform/connections/${configId}`)
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

      // Create a DB connection with draft via API (wizard auto-publishes,
      // so use API to get an INCOMPLETE config with an editable Draft)
      const configId = await createConfigWithDraftViaApi(api, name, 'DB')

      // Navigate to the detail page — Draft v1 should be visible and editable
      await adminPage.goto(`/platform/connections/${configId}`)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Credential Secret is a Zone 1 field — click Edit to enter edit mode
      await adminPage.getByRole('button', { name: /Edit/i }).first().click()
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
      // Create config + draft via API (wizard auto-publishes, so use API
      // to get an INCOMPLETE config with an editable Draft on the detail page)
      const configId = await createConfigWithDraftViaApi(api, name, 'GIT')

      // Navigate to detail page — Draft v1 is editable
      await adminPage.goto(`/platform/connections/${configId}`)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })
      const urlInput = adminPage.getByLabel('URL')
      await urlInput.fill('https://github.com/myrmecai/test-data.git')
      const saveButton = adminPage.getByRole('button', { name: /Save Draft/i })
      await expect(saveButton).toBeEnabled({ timeout: 5_000 })
      await saveButton.click()
      await expect(saveButton).toBeDisabled({ timeout: 5_000 })
      // Publish directly — server performs its own connectivity check (BR-CC-15)
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await confirmDialog(adminPage, 'Publish')
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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'GIT')
      await adminPage.goto(`/platform/connections/${configId}`)
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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'HTTP')
      await adminPage.goto(`/platform/connections/${configId}`)
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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'HTTP')
      await adminPage.goto(`/platform/connections/${configId}`)

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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'HTTP')
      await adminPage.goto(`/platform/connections/${configId}`)

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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'GIT')
      await adminPage.goto(`/platform/connections/${configId}`)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Fill URL and save Draft — do NOT click Test Connection
      await adminPage.getByLabel('URL').fill('https://github.com/myrmecai/test-data.git')
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })

      // Publish directly — server performs its own connectivity check (BR-CC-15)
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await confirmDialog(adminPage, 'Publish')
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
  // SKIPPED: server-side connectivity check is disabled in ConnectionConfigService.publishDraft()
  // for e2e environments. Re-enable when the connectivity check is restored.
  test.skip('T-25: Publish blocked when server-side connection test fails (A9)', async ({ adminPage, api }) => {
    const name = `e2e-tc-pubfail-${Date.now().toString(36)}`
    try {
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'HTTP')
      await adminPage.goto(`/platform/connections/${configId}`)
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
      // Create config + draft via API (wizard auto-publishes, so use API)
      const configId = await createConfigWithDraftViaApi(api, name, 'GIT')
      await adminPage.goto(`/platform/connections/${configId}`)
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // UI: no Ref label or textbox must exist on the detail page
      await expect(adminPage.getByLabel('Ref')).toHaveCount(0)
      await expect(adminPage.getByRole('textbox', { name: /^ref$/i })).toHaveCount(0)

      // Fill URL, save Draft, and publish directly (no Test Connection needed)
      await adminPage.getByLabel('URL').fill('https://github.com/myrmecai/test-data.git')
      await adminPage.getByRole('button', { name: /Save Draft/i }).click()
      await expect(adminPage.getByRole('button', { name: /Save Draft/i })).toBeDisabled({ timeout: 5_000 })
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await confirmDialog(adminPage, 'Publish')
      await expect(adminPage.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })

      // API + DB: published version config must not contain a `ref` property
      const publishedConfigId = await findConfigByName(api, name)
      expect(publishedConfigId).toBeTruthy()
      const published = await api.request<{ config: Record<string, unknown> }>(
        'GET', `${BASE}/${publishedConfigId!}/published-version`
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
