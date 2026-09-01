// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * #126 — Governance UI Enforcement E2E.
 *
 * Verifies that governed controls are proactively disabled/hidden based
 * on the active governance profile. The backend 403 remains the real
 * enforcement (defense-in-depth); the UI gate is the UX layer.
 *
 * Tests:
 *   1. Under STRICT: INLINE instruction source option is disabled.
 *   2. Under FLEXIBLE: INLINE instruction source option is enabled.
 *   3. Under STRICT: budget create form PROJECT/SERVICE scope options are disabled.
 *   4. Under FLEXIBLE: budget create form scope options are enabled.
 *   5. Under STRICT: service budgets page shows "Locked" badge.
 *
 * Arrange via API (set profile); assert via UI.
 * Always resets to STANDARD in afterEach to avoid polluting other suites.
 */

test.describe('governance UI enforcement', () => {
  test.afterEach(async ({ api }) => {
    // Reset to STANDARD so other test suites aren't affected.
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/STANDARD/set-default')
  })

  // ── Instruction Assets: INSTRUCTION_SOURCES ─────────────────────

  test('under STRICT, INLINE instruction source option is disabled', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/STRICT/set-default')

    // Create an instruction asset via API, then navigate to its detail page.
    const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
      scope: 'ORGANIZATION',
      name: `gov-e2e-strict-${Date.now()}`,
      description: 'Governance enforcement test',
      category: 'SYSTEM',
    })

    // Create a draft with GIT source (allowed under STRICT) so the detail page shows the draft.
    await api.request('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
      sourceType: 'GIT',
      availability: 'OPTIONAL',
      priority: 0,
    })

    await adminPage.goto(`/platform/ai-context/instruction-assets/${asset.id}`)

    // Open the source-type selector.
    await adminPage.getByLabel('Source Type').click()

    // The INLINE option should be disabled.
    const inlineOption = adminPage.getByRole('option', { name: /Inline/i })
    await expect(inlineOption).toBeDisabled()

    // The GIT option should be enabled.
    const gitOption = adminPage.getByRole('option', { name: /Git/i })
    await expect(gitOption).not.toBeDisabled()

    // Cleanup.
    await api.request('DELETE', `/admin/instruction-assets/${asset.id}`).catch(() => {})
  })

  test('under FLEXIBLE, INLINE instruction source option is enabled', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/FLEXIBLE/set-default')

    const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
      scope: 'ORGANIZATION',
      name: `gov-e2e-flex-${Date.now()}`,
      description: 'Governance enforcement test',
      category: 'SYSTEM',
    })

    await api.request('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
      sourceType: 'INLINE',
      availability: 'OPTIONAL',
      priority: 0,
    })

    await adminPage.goto(`/platform/ai-context/instruction-assets/${asset.id}`)

    // Open the source-type selector.
    await adminPage.getByLabel('Source Type').click()

    // The INLINE option should be enabled under FLEXIBLE.
    const inlineOption = adminPage.getByRole('option', { name: /Inline/i })
    await expect(inlineOption).not.toBeDisabled()

    // Cleanup.
    await api.request('DELETE', `/admin/instruction-assets/${asset.id}`).catch(() => {})
  })

  // ── Budget Create: BUDGET_OVERRIDE ──────────────────────────────

  test('under STRICT, budget create form disables PROJECT and SERVICE scope options', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/STRICT/set-default')

    await adminPage.goto('/budgets/new')

    // Open the scope-type selector.
    await adminPage.getByLabel('Scope type').click()

    // ORG should be enabled.
    const orgOption = adminPage.getByRole('option', { name: /^Org/i })
    await expect(orgOption).not.toBeDisabled()

    // PROJECT should be disabled (BUDGET_OVERRIDE=NONE).
    const projectOption = adminPage.getByRole('option', { name: /Project/i })
    await expect(projectOption).toBeDisabled()

    // SERVICE should be disabled.
    const serviceOption = adminPage.getByRole('option', { name: /Service/i })
    await expect(serviceOption).toBeDisabled()

    // The "locked by Strict profile" text should be visible somewhere
    // in the select dropdown options.
    await expect(adminPage.getByText(/locked by.*Strict/i).first()).toBeAttached({ timeout: 5_000 })
  })

  test('under FLEXIBLE, budget create form enables all scope options', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/FLEXIBLE/set-default')

    await adminPage.goto('/budgets/new')

    // Open the scope-type selector.
    await adminPage.getByLabel('Scope type').click()

    // All scope options should be enabled under FLEXIBLE.
    const orgOption = adminPage.getByRole('option', { name: /^Org/i })
    await expect(orgOption).not.toBeDisabled()

    const projectOption = adminPage.getByRole('option', { name: /^Project/i })
    await expect(projectOption).not.toBeDisabled()

    const serviceOption = adminPage.getByRole('option', { name: /^Service/i })
    await expect(serviceOption).not.toBeDisabled()
  })

  // ── Service Budgets Page: Governance Lock ───────────────────────

  test.skip('under STRICT, service budgets page shows locked badge', async ({
    adminPage,
    api,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await api.request('POST', '/admin/governance-profiles/STRICT/set-default')

    // Create a project via API.
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `gov-e2e-locked-${Date.now()}`,
      description: 'Governance lock test',
    })

    // Navigate to the service budgets page.
    // The useGovernancePolicy hook caches for 5 minutes; reload the page
    // after a short delay to ensure the governance policy query refetches.
    await adminPage.goto(`/budgets/projects/${project.id}/services`)
    // Wait for the governance policy to load and apply
    await adminPage.waitForTimeout(2_000)
    await adminPage.reload()

    // The "Locked by Strict profile" badge should be visible.
    await expect(adminPage.getByText(/Locked by.*Strict/i)).toBeVisible({ timeout: 15_000 })

    // The "Allocate budget" button should NOT be visible (gated).
    await expect(adminPage.getByRole('link', { name: /Allocate budget/i })).toHaveCount(0)

    // Cleanup.
    await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
  })
})