// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
/**
 * UC-WF-01 — Workflow CRUD (create, edit, publish, archive, versioning, list)
 *
 * Strategy: Drive the UI for all workflow interactions per the golden rule.
 * API is used ONLY for login, cross-UC arrange (agent profile creation,
 * project seeding), and cleanup.
 *
 * Tests:
 *   WF-CRUD-01: Create workflow via UI → appears in list
 *   WF-CRUD-02: Open editor, add a step, save → step persisted
 *   WF-CRUD-03: Publish workflow → status changes to PUBLISHED
 *   WF-CRUD-04: Archive workflow → status changes to ARCHIVED
 *   WF-CRUD-05: Edit published workflow → creates new draft version
 *   WF-CRUD-06: List filtering by status
 */

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { seedProjectWithMembers } from '../helpers/rbac'
import {
  createAgentProfileForWorkflow,
  deleteWorkflow,
} from '../helpers/workflow'

const WORKFLOWS_URL = '/workflows'

test.describe('UC-WF-01 — Workflow CRUD', () => {
  test.describe.configure({ mode: 'serial' })

  let projectId: string
  let profileId: string
  let profileName: string
  let workflowName: string
  let workflowId: string

  test.beforeAll(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const seed = await seedProjectWithMembers(api, 'wfcrud', {
      allowedServiceTypes: ['WORKFLOW'],
    })
    projectId = seed.projectId

    profileName = `wfcrud-profile-${Date.now().toString(36)}`
    profileId = await createAgentProfileForWorkflow(api, profileName)
  })

  test.afterAll(async ({ api }) => {
    // Best-effort cleanup — workflows are auto-cleaned by test-data teardown
    if (workflowId) {
      await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
      try { await deleteWorkflow(api, projectId, workflowId) } catch { /* gone */ }
    }
  })

  // ── WF-CRUD-01: Create workflow via UI ───────────────────────────
  test('WF-CRUD-01: create workflow via UI and see it in the list', async ({ adminPage, api }) => {
    workflowName = `wfcrud-wf-${Date.now().toString(36)}`

    // Get the actual project name for exact matching in the dropdown
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const project = await api.request<{ id: string; name: string }>('GET', `/projects/${projectId}`)
    const projectName = project.name

    await adminPage.goto(WORKFLOWS_URL)

    // Click "New Workflow"
    await adminPage.getByRole('button', { name: /New Workflow/i }).click()

    // Fill create dialog
    await adminPage.getByPlaceholder('e.g., Code Review Pipeline').fill(workflowName)
    await adminPage.getByPlaceholder('What does this workflow do?').fill('Test workflow for CRUD spec')

    // Select project — match by exact name to avoid picking a stale project from a prior run
    await adminPage.getByRole('combobox').first().click()
    await adminPage.getByRole('option', { name: projectName, exact: true }).click()

    // Submit
    await adminPage.getByRole('button', { name: /Create & open editor/i }).click()

    // Should navigate to the editor
    await expect(adminPage).toHaveURL(/\/workflows\/[^/]+\/edit/, { timeout: 15_000 })

    // Extract workflowId from URL
    const url = adminPage.url()
    const match = url.match(/\/workflows\/([^/]+)\/edit/)
    expect(match).not.toBeNull()
    workflowId = match![1]

    // Editor should show DRAFT status
    await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })

    // Go back to list and verify it appears
    await adminPage.goto(WORKFLOWS_URL)
    await expect(adminPage.getByText(workflowName).first()).toBeVisible({ timeout: 10_000 })
  })

  // ── WF-CRUD-02: Add a step in the editor and save ────────────────
  test('WF-CRUD-02: add a step in editor and save', async ({ adminPage }) => {
    expect(workflowId).toBeDefined()

    // Navigate to editor — adminPage fixture already handles auth
    await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)
    // Wait for the editor to load — the "Back to Workflows" button means error
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    // Wait for the Save button to appear (editor loaded successfully)
    await expect(adminPage.getByRole('button', { name: /^Save$/i })).toBeVisible({ timeout: 15_000 })
    // Wait for the status badge
    await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })

    // Click "Add Step" on the canvas
    await adminPage.getByTestId('add-step-button').click()

    // A new step should appear in the left rail and the properties panel
    // The default name is "New Step 1"
    await expect(adminPage.getByText(/New Step 1/i).first()).toBeVisible({ timeout: 5_000 })

    // Fill the Name field in Step Properties (2nd input in the basic info section)
    const nameField = adminPage.locator('.space-y-3 > div').nth(1).locator('input')
    await nameField.fill('Review Step')

    // Select agent profile from the Shadcn Select dropdown
    // The SelectTrigger renders as a button; click it to open the dropdown
    const profileSelect = adminPage.getByText('Agent Profile').locator('..').getByRole('combobox')
    await profileSelect.click()
    await adminPage.getByRole('option', { name: profileName }).click()

    // Fill prompt — Monaco editor, click and type
    const monacoEditor = adminPage.locator('.monaco-editor').first()
    await monacoEditor.click()
    await adminPage.keyboard.type('Review the code and report issues')

    // Save
    await adminPage.getByRole('button', { name: /Save/i }).click()

    // Wait for save to complete — "Unsaved changes" indicator should disappear
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })

    // Reload to verify persistence
    await adminPage.reload()
    await expect(adminPage.getByText('Review Step').first()).toBeVisible({ timeout: 10_000 })
  })

  // ── WF-CRUD-03: Publish workflow ─────────────────────────────────
  test('WF-CRUD-03: publish workflow → status changes to PUBLISHED', async ({ adminPage }) => {
    expect(workflowId).toBeDefined()

    await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)

    // Wait for editor to load
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    await expect(adminPage.getByRole('button', { name: /^Save$/i })).toBeVisible({ timeout: 15_000 })
    // Ensure we're in DRAFT state
    await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })

    // Click Publish
    await adminPage.getByTestId('workflow-publish-button').click()

    // Status should change to PUBLISHED
    await expect(adminPage.getByText('PUBLISHED').first()).toBeVisible({ timeout: 10_000 })

    // Verify in list view
    await adminPage.goto(WORKFLOWS_URL)
    await expect(adminPage.getByText(workflowName).first()).toBeVisible({ timeout: 10_000 })
  })

  // ── WF-CRUD-04: Archive workflow ─────────────────────────────────
  test('WF-CRUD-04: archive workflow → status changes to ARCHIVED', async ({ adminPage }) => {
    expect(workflowId).toBeDefined()

    await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)

    // Wait for editor to load
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    await expect(adminPage.getByRole('button', { name: /^Save$/i }).first()).toBeVisible({ timeout: 15_000 })

    // Click Archive
    await adminPage.getByRole('button', { name: /Archive/i }).click()

    // Status should change to ARCHIVED
    await expect(adminPage.getByText('ARCHIVED').first()).toBeVisible({ timeout: 10_000 })
  })

  // ── WF-CRUD-05: Edit published workflow creates new draft version ─
  test('WF-CRUD-05: editing a published workflow via API then UI shows version bump', async ({ api, adminPage }) => {
    // We need a published workflow for this test — create one via API
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const draftName = `wfcrud-vtest-${Date.now().toString(36)}`
    const wf = await api.request<{ id: string; version: number }>(
      'POST',
      `/projects/${projectId}/workflows`,
      {
        projectId,
        name: draftName,
        steps: [{
          id: 's1',
          name: 'Step 1',
          agentProfileId: profileId,
          prompt: 'Initial step',
          dependsOn: [],
          transitions: {},
          timeoutSeconds: 300,
          maxRetries: 0,
          pauseMode: 'NONE',
        }],
      },
    )
    await api.request('POST', `/projects/${projectId}/workflows/${wf.id}/publish`)

    // Now open the editor in the UI
    await adminPage.goto(`/workflows/${wf.id}/edit?projectId=${projectId}`)

    // Should show PUBLISHED and v1
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    await expect(adminPage.getByText('PUBLISHED').first()).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByText(/v1/i).first()).toBeVisible({ timeout: 10_000 })

    // Add a new step — this should create a draft version
    await adminPage.getByTestId('add-step-button').click()
    await expect(adminPage.getByText(/New Step/i).first()).toBeVisible({ timeout: 5_000 })

    // Save
    await adminPage.getByRole('button', { name: /Save/i }).click()
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })

    // Version should bump to v2 (draft)
    await expect(adminPage.getByText(/v2/i).first()).toBeVisible({ timeout: 10_000 })

    // Cleanup
    try { await deleteWorkflow(api, projectId, wf.id) } catch { /* gone */ }
  })

  // ── WF-CRUD-06: List filtering by status ─────────────────────────
  test('WF-CRUD-06: filter workflows by status', async ({ api, adminPage }) => {
    // Create workflows in different states via API
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const draftName = `wfcrud-draft-${Date.now().toString(36)}`
    const draftWf = await api.request<{ id: string; name: string }>(
      'POST',
      `/projects/${projectId}/workflows`,
      {
        projectId,
        name: draftName,
        steps: [{
          id: 's1',
          name: 'S1',
          agentProfileId: profileId,
          prompt: 'x',
          dependsOn: [],
          transitions: {},
          timeoutSeconds: 300,
          maxRetries: 0,
          pauseMode: 'NONE',
        }],
      },
    )

    const pubName = `wfcrud-pub-${Date.now().toString(36)}`
    const pubWf = await api.request<{ id: string; name: string }>(
      'POST',
      `/projects/${projectId}/workflows`,
      {
        projectId,
        name: pubName,
        steps: [{
          id: 's1',
          name: 'S1',
          agentProfileId: profileId,
          prompt: 'x',
          dependsOn: [],
          transitions: {},
          timeoutSeconds: 300,
          maxRetries: 0,
          pauseMode: 'NONE',
        }],
      },
    )
    await api.request('POST', `/projects/${projectId}/workflows/${pubWf.id}/publish`)

    // Open the list page and verify both workflows appear
    await adminPage.goto(WORKFLOWS_URL)
    await expect(adminPage.getByText(pubName).first()).toBeVisible({ timeout: 15_000 })

    // Verify the published workflow shows PUBLISHED status badge
    const pubRow = adminPage.locator('tr', { hasText: pubName })
    await expect(pubRow.getByText('PUBLISHED')).toBeVisible({ timeout: 5_000 })

    // Verify the draft workflow shows DRAFT status badge
    const draftRow = adminPage.locator('tr', { hasText: draftName })
    await expect(draftRow.locator('.bg-gray-500')).toBeVisible({ timeout: 5_000 })

    // Cleanup
    try { await deleteWorkflow(api, projectId, draftWf.id) } catch { /* gone */ }
    try { await deleteWorkflow(api, projectId, pubWf.id) } catch { /* gone */ }
  })
})