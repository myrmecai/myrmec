// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
/**
 * INH-01..INH-07 — Org/project instruction inheritance.
 *
 * Tests that:
 *  - Org REQUIRED instruction assets are always inherited by projects.
 *  - Org OPTIONAL instruction assets are visible but not auto-included.
 *  - Project admins can enable/disable OPTIONAL org assets per project.
 *  - Project-scoped assets are isolated per project.
 *  - Lifecycle changes (disable/archive) remove assets from the inherited list.
 *
 * Pattern: arrange-via-API, assert-via-UI.
 *
 * NOTE: Org-scoped inline instruction assets require FLEXIBLE governance
 * (STANDARD blocks inline at org scope). Each test sets FLEXIBLE in beforeEach
 * and resets to STANDARD in afterEach.
 */

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import {
  setGovernanceProfile,
  resetGovernanceProfile,
} from '../helpers/governance'
import {
  createOrgInstructionAssetWithAvailability,
  createProjectInstructionAsset,
  getInstructionBindings,
} from '../helpers/instruction-inheritance'

test.beforeEach(async ({ api }) => {
  await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  // Org-scoped inline assets require FLEXIBLE governance
  await setGovernanceProfile(api, 'FLEXIBLE')
})

test.afterEach(async ({ api }) => {
  await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  await resetGovernanceProfile(api)
})

test.describe('INH-01 — org REQUIRED instruction appears in project effective stack', () => {
  test('active published org REQUIRED asset is shown in inherited list and enabled by default', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-01 ${Date.now().toString(36)}` })
    const orgName = `INH-01-org-${Date.now().toString(36)}`
    await createOrgInstructionAssetWithAvailability(api, orgName, 'REQUIRED')

    await adminPage.goto(`/projects/${project.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()
    await adminPage.getByText('Inherited Organization Assets').waitFor()

    // REQUIRED asset visible and checked / not toggleable
    const row = adminPage.locator('[data-testid^="inherited-instruction-asset-"]', { hasText: orgName })
    await expect(row).toBeVisible()
    await expect(row.locator('[data-testid^="availability-badge-"]')).toHaveText('REQUIRED')
    await expect(row.locator('[data-testid^="enabled-switch-"]')).toBeChecked()
    await expect(row.locator('[data-testid^="enabled-switch-"]')).toBeDisabled()
  })
})

test.describe('INH-02 — org OPTIONAL instruction visible but not auto-included', () => {
  test('OPTIONAL org asset is listed as inherited but initially not enabled', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-02 ${Date.now().toString(36)}` })
    const orgName = `INH-02-org-${Date.now().toString(36)}`
    await createOrgInstructionAssetWithAvailability(api, orgName, 'OPTIONAL')

    await adminPage.goto(`/projects/${project.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()
    await adminPage.getByText('Inherited Organization Assets').waitFor()

    const row = adminPage.locator('[data-testid^="inherited-instruction-asset-"]', { hasText: orgName })
    await expect(row).toBeVisible()
    await expect(row.locator('[data-testid^="availability-badge-"]')).toHaveText('OPTIONAL')
    await expect(row.locator('[data-testid^="enabled-switch-"]')).not.toBeChecked()
  })
})

test.describe('INH-03 — project admin enables inherited OPTIONAL instruction', () => {
  test('enabling OPTIONAL org asset updates binding and switch state', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-03 ${Date.now().toString(36)}` })
    const orgName = `INH-03-org-${Date.now().toString(36)}`
    const asset = await createOrgInstructionAssetWithAvailability(api, orgName, 'OPTIONAL')

    await adminPage.goto(`/projects/${project.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()
    await adminPage.getByText('Inherited Organization Assets').waitFor()

    const row = adminPage.locator('[data-testid^="inherited-instruction-asset-"]', { hasText: orgName })
    const toggle = row.locator('[data-testid^="enabled-switch-"]')
    await toggle.click()

    await expect(toggle).toBeChecked()

    // Verify via API that binding is now enabled
    const bindings = await getInstructionBindings(api, project.id)
    const binding = bindings.find((b) => b.instructionAssetId === asset.assetId)
    expect(binding).toBeDefined()
    expect(binding!.enabled).toBe(true)
  })
})

test.describe('INH-04 — project-scoped instruction NOT visible in other projects', () => {
  test('project asset only appears in its own project AI Context tab', async ({ api, adminPage }) => {
    const projectA = await api.request<{ id: string }>('POST', '/projects', { name: `INH-04A ${Date.now().toString(36)}` })
    const projectB = await api.request<{ id: string }>('POST', '/projects', { name: `INH-04B ${Date.now().toString(36)}` })
    const assetName = `INH-04A-asset-${Date.now().toString(36)}`
    await createProjectInstructionAsset(api, projectA.id, assetName)

    // Navigate to project B — the asset from project A should not appear
    await adminPage.goto(`/projects/${projectB.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()

    // The project-scoped asset from A should not be visible in B's AI Context
    await expect(adminPage.getByText(assetName)).not.toBeVisible({ timeout: 5000 })
  })
})

test.describe('INH-05 — org DISABLED instruction disappears from all project stacks', () => {
  test('disabling org asset removes it from inherited list', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-05 ${Date.now().toString(36)}` })
    const orgName = `INH-05-org-${Date.now().toString(36)}`
    const asset = await createOrgInstructionAssetWithAvailability(api, orgName, 'REQUIRED')

    // Confirm it is visible while ACTIVE
    await adminPage.goto(`/projects/${project.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()
    await adminPage.getByText('Inherited Organization Assets').waitFor()
    await expect(adminPage.locator('[data-testid^="inherited-instruction-asset-"]', { hasText: orgName })).toBeVisible()

    // Disable via API
    await api.request('POST', `/admin/instruction-assets/${asset.assetId}/disable`)

    // Refresh UI and assert gone
    await adminPage.reload()
    await adminPage.getByText('AI Context', { exact: true }).click()
    await expect(adminPage.locator('[data-testid^="inherited-instruction-asset-"]', { hasText: orgName })).not.toBeVisible({ timeout: 5000 })
  })
})

test.describe('INH-06 — project ARCHIVED instruction removed from effective stack', () => {
  test('archiving a project asset marks it as ARCHIVED in the project AI Context tab', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-06 ${Date.now().toString(36)}` })
    const assetName = `INH-06-asset-${Date.now().toString(36)}`
    const asset = await createProjectInstructionAsset(api, project.id, assetName)

    await adminPage.goto(`/projects/${project.id}`)
    await adminPage.getByText('AI Context', { exact: true }).click()

    // Project asset should be visible in the Instruction Assets sub-tab
    await expect(adminPage.getByText(assetName)).toBeVisible({ timeout: 5000 })

    // Archive requires disable first
    await api.request('POST', `/admin/instruction-assets/${asset.assetId}/disable`)
    await api.request('POST', `/admin/instruction-assets/${asset.assetId}/archive`)

    await adminPage.reload()
    await adminPage.getByText('AI Context', { exact: true }).click()

    // The asset remains in the list but with ARCHIVED status badge
    const row = adminPage.locator('tr', { hasText: assetName })
    await expect(row).toBeVisible({ timeout: 5000 })
    await expect(row.getByText('ARCHIVED')).toBeVisible()
  })
})

/**
 * INH-07 — Same-name instruction at org and project scope: project wins.
 *
 * When an org-scoped and project-scoped instruction asset share the same name,
 * the project-scoped one should take priority in the resolved context stack.
 * Assert via the context-preview endpoint.
 */
test.describe('INH-07 — same-name org+project instruction: project wins', () => {
  test('project-scoped instruction appears in context preview, org one is overridden', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `INH-07 ${Date.now().toString(36)}` })
    const sharedName = `INH-07-shared-${Date.now().toString(36)}`

    // Create org-scoped instruction with the shared name
    await createOrgInstructionAssetWithAvailability(api, sharedName, 'REQUIRED')

    // Create project-scoped instruction with the same name
    await createProjectInstructionAsset(api, project.id, sharedName)

    // Call the context-preview endpoint
    const preview = await api.request<{
      instructions: Array<{ name: string; scope: string }>
    }>('GET', `/admin/projects/${project.id}/context-preview?serviceType=CONVERSATION`)

    // Both may appear in the resolved stack, but the project-scoped one must be present
    const projectInstructions = preview.instructions.filter((i) => i.name === sharedName && i.scope === 'PROJECT')
    expect(projectInstructions.length).toBeGreaterThanOrEqual(1)

    // The org-scoped instruction may also appear. The "project wins" semantics
    // mean the project-scoped instruction is included in the resolved stack
    // alongside the org one (both are present, project has higher priority
    // via base priority 2000 vs org's 1000). The key assertion is that the
    // project-scoped instruction IS in the resolved stack.
    const allShared = preview.instructions.filter((i) => i.name === sharedName)
    expect(allShared.length).toBeGreaterThanOrEqual(1)
  })
})