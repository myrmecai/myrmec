// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import type { Page } from '@playwright/test'
import { E2E_ADMIN } from '../helpers/api'
import { createVersionedEntityTests, type VersionedEntityConfig } from './versioned-entity-standard.spec'

/**
 * UC-KM-03 - Manage Org Knowledge Providers
 *
 * Single spec file containing:
 *   1. Standard versioned-entity tests (VE-S01 through VE-S45)
 *      - Defined in: e2e-testing-standards.md §7
 *      - Source: governance-and-versioning.md §3, ui-standards.md Patterns 1-7
 *   2. Entity-specific tests (T-KP-01 through T-KP-12)
 *      - See: UC-KM-03-manage-org-knowledge-providers.md §13
 *
 * Strategy: Drive the UI for all interactions (per e2e-testing-standards.md).
 * API is used ONLY for login, cleanup, and cross-UC setup.
 *
 * Key differences from Instruction Assets (UC-KM-02):
 *   - Quick-Create auto-creates a Draft and navigates to detail page (UC-KM-03 Step 3)
 *   - Zone 1 IS editable (Name, Description) — UC-KM-03 Step 5
 *   - Type is immutable after creation (External only for initial release)
 *   - Zone 2 fields: Connection Config, Max Top-K, Similarity Threshold,
 *     Response Mapping (5 JSONPath fields), Timeout
 *   - Knowledge Sources section: Add/Edit via dialog, Delete inline with confirmation
 *   - Sources are locked to the version — editable only when a Draft exists
 */

const BASE = '/admin/knowledge-providers'

// === Helper functions ===

async function cleanupProvider(api: any, providerId: string): Promise<void> {
  try { await api.request('POST', `${BASE}/${providerId}/disable`) } catch { /* ignore */ }
  try { await api.request('POST', `${BASE}/${providerId}/archive`) } catch { /* ignore */ }
  try { await api.request('DELETE', `${BASE}/${providerId}`) } catch { /* gone */ }
}

async function findProviderByName(api: any, name: string): Promise<string | null> {
  const providers = await api.request('GET', BASE)
  const found = (providers as any[]).find((p) => p.name === name)
  return found?.id ?? null
}

/**
 * Custom create-and-publish flow for Knowledge Providers:
 * 1. Quick-Create (Name + Type) → auto-navigates to detail with Draft created
 * 2. Fill Zone 2 config fields, save draft, publish
 */
async function createProviderAndPublish(page: Page, _config: VersionedEntityConfig, name: string): Promise<void> {
  await page.goto('/platform/ai-context/knowledge-providers')
  await page.getByRole('button', { name: 'New Provider' }).click()
  await page.getByLabel('Name').fill(name)
  await page.getByRole('button', { name: /Create/i }).click()
  // Auto-navigates to detail page with Draft already created (Pattern 4 §Rule 4)
  await expect(page).toHaveURL(/\/platform\/ai-context\/knowledge-providers\/[^/]+$/, { timeout: 10_000 })
  await expect(page.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

  // Fill required Zone 2 fields
  await page.getByLabel('Hits Path').fill('$.results')
  await page.getByLabel('Passage Path').fill('$.text')
  await page.getByLabel('Source Name Path').fill('$.source')
  await page.getByLabel('Locator Path').fill('$.url')

  // Save Draft
  const saveBtn = page.getByRole('button', { name: /Save Draft/i })
  await expect(saveBtn).toBeEnabled({ timeout: 5_000 })
  await saveBtn.click()
  await expect(saveBtn).toBeDisabled({ timeout: 5_000 })

  // Publish
  await page.getByRole('button', { name: /Publish/i }).click()
  await expect(page.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })
}

/**
 * Custom fill Draft fields and save.
 * Fills the required response mapping fields so the publish gate passes.
 */
async function fillProviderDraftAndSave(page: Page, _config: VersionedEntityConfig): Promise<void> {
  const hitsInput = page.getByLabel('Hits Path')
  if (await hitsInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await hitsInput.fill('$.results')
  }
  const passageInput = page.getByLabel('Passage Path')
  if (await passageInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await passageInput.fill('$.text')
  }
  const sourceNameInput = page.getByLabel('Source Name Path')
  if (await sourceNameInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await sourceNameInput.fill('$.source')
  }
  const locatorInput = page.getByLabel('Locator Path')
  if (await locatorInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await locatorInput.fill('$.url')
  }
  const saveBtn = page.getByRole('button', { name: /Save Draft/i })
  if (await saveBtn.isEnabled({ timeout: 3_000 }).catch(() => false)) {
    await saveBtn.click()
    await expect(saveBtn).toBeDisabled({ timeout: 5_000 })
  }
}

// === Standard Versioned Entity Tests ===
createVersionedEntityTests({
  entityName: 'Knowledge Provider',
  listRoute: '/platform/ai-context/knowledge-providers',
  detailRoutePattern: /\/platform\/ai-context\/knowledge-providers\/[^/]+$/,
  apiBase: BASE,
  listHeading: 'Knowledge Providers',
  listCardTitle: 'Organization-Scoped Providers',
  createButtonText: 'New Provider',
  createDialogHeading: 'Create Knowledge Provider',
  nameLabel: 'Name',
  // Knowledge Providers don't have a URL field — use custom fill
  draftUrlLabel: undefined,
  draftUrlValue: undefined,
  draftUrlValueV2: undefined,
  // Quick-Create auto-creates a Draft and navigates to detail page (Pattern 4 §Rule 4)
  autoCreateDraft: true,
  // Zone 1 is editable
  zone1Editable: true,
  // Publish gate validates required fields (connection config, response mapping paths)
  hasPublishGate: true,
  // Custom create-and-publish flow
  customCreateDraftAndPublish: createProviderAndPublish,
  customFillDraftAndSave: fillProviderDraftAndSave,
  adminEmail: E2E_ADMIN.email,
  adminPassword: E2E_ADMIN.password,
})

// === Entity-Specific Tests ===

test.describe('UC-KM-03 knowledge providers - entity-specific tests', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // T-KP-01: Quick-create dialog shows Name + Type (2 fields)
  test('T-KP-01: Quick-create dialog shows Name and Type', async ({ adminPage, api }) => {
    const name = `e2e-kp-dialog-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/ai-context/knowledge-providers')
      await adminPage.getByRole('button', { name: 'New Provider' }).click()
      await expect(adminPage.getByRole('heading', { name: 'Create Knowledge Provider' })).toBeVisible()
      await expect(adminPage.getByLabel('Name')).toBeVisible()
      // Type dropdown should be visible
      await expect(adminPage.locator('label[for="type"]')).toBeVisible()
      // [Create] should be disabled until Name filled
      const createButton = adminPage.getByRole('button', { name: /Create/i })
      await expect(createButton).toBeDisabled()
      await adminPage.getByLabel('Name').fill(name)
      await expect(createButton).toBeEnabled()
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-02: Type dropdown shows External
  test('T-KP-02: Type dropdown shows External', async ({ adminPage, api }) => {
    const name = `e2e-kp-type-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/ai-context/knowledge-providers')
      await adminPage.getByRole('button', { name: 'New Provider' }).click()
      await expect(adminPage.getByRole('heading', { name: 'Create Knowledge Provider' })).toBeVisible()

      // Open the Type dropdown
      await adminPage.locator('label[for="type"]').locator('..').locator('button').first().click()

      // For initial release, only External is available
      await expect(adminPage.getByRole('option', { name: /External/i })).toBeVisible({ timeout: 5_000 })

      // Close the dropdown
      await adminPage.keyboard.press('Escape')
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-03: Provider config saves correctly (connection, response mapping)
  test('T-KP-03: Provider config saves correctly', async ({ adminPage, api }) => {
    const name = `e2e-kp-config-${Date.now().toString(36)}`
    try {
      await adminPage.goto('/platform/ai-context/knowledge-providers')
      await adminPage.getByRole('button', { name: 'New Provider' }).click()
      await adminPage.getByLabel('Name').fill(name)
      await adminPage.getByRole('button', { name: /Create/i }).click()
      await expect(adminPage).toHaveURL(/\/platform\/ai-context\/knowledge-providers\/[^/]+$/, { timeout: 10_000 })
      await expect(adminPage.getByText(/Draft.*v1/i)).toBeVisible({ timeout: 10_000 })

      // Fill Zone 2 fields
      await adminPage.getByLabel('Hits Path').fill('$.results')
      await adminPage.getByLabel('Passage Path').fill('$.text')
      await adminPage.getByLabel('Source Name Path').fill('$.source')
      await adminPage.getByLabel('Locator Path').fill('$.url')
      await adminPage.getByLabel('Score Path').fill('$.score')

      // Fill Max Top-K and Similarity Threshold
      const topKInput = adminPage.getByLabel('Max Top-K')
      if (await topKInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await topKInput.fill('20')
      }
      const thresholdInput = adminPage.getByLabel('Similarity Threshold')
      if (await thresholdInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await thresholdInput.fill('0.3')
      }

      // Fill Timeout
      const timeoutInput = adminPage.getByLabel('Timeout (ms)')
      if (await timeoutInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await timeoutInput.fill('10000')
      }

      // Save Draft
      const saveBtn = adminPage.getByRole('button', { name: /Save Draft/i })
      await expect(saveBtn).toBeEnabled({ timeout: 5_000 })
      await saveBtn.click()
      await expect(saveBtn).toBeDisabled({ timeout: 5_000 })

      // Verify saved values visible
      await expect(adminPage.getByText('$.results').first()).toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByText('$.text').first()).toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByText('$.source').first()).toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByText('$.url').first()).toBeVisible({ timeout: 5_000 })

      // Publish
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })

      // Verify values persist in published version
      await expect(adminPage.getByText('$.results').first()).toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByText('$.text').first()).toBeVisible({ timeout: 5_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-04: Type is immutable after creation (disabled in edit mode)
  test('T-KP-04: Type is immutable after creation', async ({ adminPage, api }) => {
    const name = `e2e-kp-immutable-${Date.now().toString(36)}`
    try {
      // Create and publish
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Click Edit in Zone 1
      await adminPage.getByRole('button', { name: /Edit/i }).click()

      // Type dropdown should be disabled
      const typeDropdown = adminPage.locator('label[for="type"]').locator('..').locator('button').first()
      await expect(typeDropdown).toBeDisabled({ timeout: 5_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-05: Provider Draft mode unlocks Source management
  test('T-KP-05: Draft mode unlocks Knowledge Source management', async ({ adminPage, api }) => {
    const name = `e2e-kp-draftunlock-${Date.now().toString(36)}`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (Draft)
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Verify [Add] button is visible in Knowledge Sources section
      await expect(adminPage.getByRole('button', { name: /Add/i })).toBeVisible({ timeout: 5_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-06: Source editing locked when Provider has no Draft
  test('T-KP-06: Source editing locked when no Draft', async ({ adminPage, api }) => {
    const name = `e2e-kp-locked-${Date.now().toString(36)}`
    try {
      // Create and publish
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // [Add] button should NOT be visible on published version (no Draft)
      await expect(adminPage.getByRole('button', { name: /Add/i })).toHaveCount(0)
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-07: Add Knowledge Source via dialog
  test('T-KP-07: Add Knowledge Source via dialog', async ({ adminPage, api }) => {
    const name = `e2e-kp-addsrc-${Date.now().toString(36)}`
    const sourceName = `e2e-ks-eng-${Date.now().toString(36)}`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (Draft)
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Click [Add] in Knowledge Sources section
      await adminPage.getByRole('button', { name: /Add/i }).click()

      // Verify dialog opens
      const dialog = adminPage.getByRole('dialog')
      await expect(dialog).toBeVisible({ timeout: 5_000 })
      await expect(dialog.getByRole('heading', { name: /Add Knowledge Source/i })).toBeVisible()

      // Fill required fields
      await dialog.getByLabel('Name').fill(sourceName)
      await dialog.getByLabel('Path').fill('/api/v1/retrieval')

      // Save
      await dialog.getByRole('button', { name: /Save/i }).click()
      await expect(dialog).not.toBeVisible({ timeout: 5_000 })

      // Verify new source appears in table
      await expect(adminPage.getByText(sourceName).first()).toBeVisible({ timeout: 5_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-08: Edit Knowledge Source via dialog
  test('T-KP-08: Edit Knowledge Source via dialog', async ({ adminPage, api }) => {
    const name = `e2e-kp-editsrc-${Date.now().toString(36)}`
    const sourceName = `e2e-ks-edit-${Date.now().toString(36)}`
    const updatedName = `${sourceName}-updated`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (Draft)
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Add a source first
      await adminPage.getByRole('button', { name: /Add/i }).click()
      const addDialog = adminPage.getByRole('dialog')
      await expect(addDialog).toBeVisible({ timeout: 5_000 })
      await addDialog.getByLabel('Name').fill(sourceName)
      await addDialog.getByLabel('Path').fill('/api/v1/retrieval')
      await addDialog.getByRole('button', { name: /Save/i }).click()
      await expect(addDialog).not.toBeVisible({ timeout: 5_000 })

      // Click [Edit] on the source row
      const sourceRow = adminPage.getByRole('row', { name: new RegExp(sourceName) })
      await expect(sourceRow).toBeVisible({ timeout: 5_000 })
      await sourceRow.getByRole('button', { name: /Edit/i }).click()

      // Verify dialog opens pre-filled
      const editDialog = adminPage.getByRole('dialog')
      await expect(editDialog).toBeVisible({ timeout: 5_000 })
      await expect(editDialog.getByRole('heading', { name: /Edit Knowledge Source/i })).toBeVisible()
      await expect(editDialog.getByLabel('Name')).toHaveValue(sourceName)

      // Modify Name and Path
      await editDialog.getByLabel('Name').fill(updatedName)
      await editDialog.getByLabel('Path').fill('/api/v2/search')

      // Save
      await editDialog.getByRole('button', { name: /Save/i }).click()
      await expect(editDialog).not.toBeVisible({ timeout: 5_000 })

      // Verify updated values in table
      await expect(adminPage.getByText(updatedName).first()).toBeVisible({ timeout: 5_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-09: Delete Knowledge Source with confirmation
  test('T-KP-09: Delete Knowledge Source with confirmation', async ({ adminPage, api }) => {
    const name = `e2e-kp-delsrc-${Date.now().toString(36)}`
    const sourceName = `e2e-ks-del-${Date.now().toString(36)}`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (Draft)
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Add a source
      await adminPage.getByRole('button', { name: /Add/i }).click()
      const addDialog = adminPage.getByRole('dialog')
      await expect(addDialog).toBeVisible({ timeout: 5_000 })
      await addDialog.getByLabel('Name').fill(sourceName)
      await addDialog.getByLabel('Path').fill('/api/v1/retrieval')
      await addDialog.getByRole('button', { name: /Save/i }).click()
      await expect(addDialog).not.toBeVisible({ timeout: 5_000 })
      await expect(adminPage.getByText(sourceName).first()).toBeVisible({ timeout: 5_000 })

      // Click [Delete] on the source row
      const sourceRow = adminPage.getByRole('row', { name: new RegExp(sourceName) })
      await sourceRow.getByRole('button', { name: /Delete/i }).click()

      // Verify confirmation dialog
      const confirmDialog = adminPage.getByRole('dialog')
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 })
      await expect(confirmDialog.getByText(new RegExp(`Delete ${sourceName}`))).toBeVisible()

      // Confirm deletion
      await confirmDialog.getByRole('button', { name: /Confirm|Delete|Yes/i }).click()
      await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 })

      // Verify source removed from table
      await expect(adminPage.getByText(sourceName)).toHaveCount(0)
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-10: Publish snapshots linked Source configs into Provider version
  test('T-KP-10: Publish snapshots linked Source configs into Provider version', async ({ adminPage, api }) => {
    const name = `e2e-kp-snapshot-${Date.now().toString(36)}`
    const sourceName = `e2e-ks-snap-${Date.now().toString(36)}`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (Draft)
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Add a source
      await adminPage.getByRole('button', { name: /Add/i }).click()
      const addDialog = adminPage.getByRole('dialog')
      await expect(addDialog).toBeVisible({ timeout: 5_000 })
      await addDialog.getByLabel('Name').fill(sourceName)
      await addDialog.getByLabel('Path').fill('/api/v1/retrieval')
      await addDialog.getByRole('button', { name: /Save/i }).click()
      await expect(addDialog).not.toBeVisible({ timeout: 5_000 })

      // Publish v2
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Verify source is visible in published version (snapshotted)
      await expect(adminPage.getByText(sourceName).first()).toBeVisible({ timeout: 5_000 })

      // Verify source editing is locked (no [Add] button on published version)
      await expect(adminPage.getByRole('button', { name: /Add/i })).toHaveCount(0)
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-11: Disable confirmation warns about Knowledge Sources
  test('T-KP-11: Disable confirmation warns about Knowledge Sources', async ({ adminPage, api }) => {
    const name = `e2e-kp-disable-${Date.now().toString(36)}`
    const sourceName = `e2e-ks-diswarn-${Date.now().toString(36)}`
    try {
      // Create, add a source, and publish
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version, add a source, publish v2
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })
      await adminPage.getByRole('button', { name: /Add/i }).click()
      const addDialog = adminPage.getByRole('dialog')
      await expect(addDialog).toBeVisible({ timeout: 5_000 })
      await addDialog.getByLabel('Name').fill(sourceName)
      await addDialog.getByLabel('Path').fill('/api/v1/retrieval')
      await addDialog.getByRole('button', { name: /Save/i }).click()
      await expect(addDialog).not.toBeVisible({ timeout: 5_000 })
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version.*v2/i)).toBeVisible({ timeout: 10_000 })

      // Go to list page and execute Disable
      await adminPage.goto('/platform/ai-context/knowledge-providers')
      const row = adminPage.getByRole('row', { name: new RegExp(name) })
      await expect(row).toBeVisible({ timeout: 10_000 })
      await row.getByRole('button', { name: 'More actions' }).click()
      await adminPage.getByRole('menuitem', { name: /Disable/i }).click()

      // Verify confirmation dialog warns about linked sources
      const confirmDialog = adminPage.getByRole('dialog')
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 })
      await expect(confirmDialog.getByText(/knowledge source/i)).toBeVisible()
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })

  // T-KP-12: Clone ARCHIVED version as Draft
  test('T-KP-12: Clone ARCHIVED version as Draft', async ({ adminPage, api }) => {
    const name = `e2e-kp-clone-${Date.now().toString(36)}`
    try {
      // Create and publish v1
      await createProviderAndPublish(adminPage, {} as VersionedEntityConfig, name)
      await expect(adminPage.getByText(/Published Version.*v1/i)).toBeVisible({ timeout: 5_000 })

      // Create new version (v2) and publish
      await adminPage.getByRole('button', { name: /New Draft Version/i }).click()
      await expect(adminPage.getByText(/Draft.*v2/i)).toBeVisible({ timeout: 10_000 })
      await adminPage.getByRole('button', { name: /Publish/i }).click()
      await expect(adminPage.getByText(/Published Version.*v2/i)).toBeVisible({ timeout: 10_000 })

      // v1 should be ARCHIVED — click [Clone as Draft] on v1 in Version History
      const cloneButton = adminPage.getByRole('button', { name: /Clone as Draft/i })
      await expect(cloneButton.first()).toBeVisible({ timeout: 5_000 })
      await cloneButton.first().click()
      await expect(adminPage.getByText(/Draft.*v3/i)).toBeVisible({ timeout: 10_000 })
    } finally {
      const id = await findProviderByName(api, name)
      if (id) await cleanupProvider(api, id)
    }
  })
})
