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
import { loginAs } from '../helpers/auth'
import { seedProjectWithMembers, type ProjectWithMembers } from '../helpers/rbac'
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
  /** The YAML authoring tests' own editable DRAFT (CRUD-04 archives the shared one). */
  let yamlWorkflowId: string
  let seed: ProjectWithMembers

  test.beforeAll(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    seed = await seedProjectWithMembers(api, 'wfcrud', {
      allowedServiceTypes: ['WORKFLOW'],
    })
    projectId = seed.projectId

    profileName = `wfcrud-profile-${Date.now().toString(36)}`
    profileId = await createAgentProfileForWorkflow(api, profileName)
  })

  /** The suite drives as the project OWNER — the platform admin holds no
   *  project data-access roles by design (separation of duties), so
   *  workflow create/edit/publish is role-gated to PROJECT_OWNER. */
  async function loginAsOwner(page: import('@playwright/test').Page) {
    await loginAs(page, seed.owner.email, seed.owner.password)
  }

  /**
   * Replace the Monaco document verbatim. Typewriter and clipboard-style
   * entry both fail for multi-line YAML: Monaco's YAML auto-indent stacks
   * on the inserted leading spaces and corrupts nested indentation
   * (persistent parse errors). Setting the model value directly bypasses
   * key processing — but the editor is CONTROLLED (`value={yamlText}`):
   * if the insert lands before the onChange listener attaches (dev-mode
   * StrictMode double-mount window), React's value-sync effect reverts
   * the model to the stale state. So the helper re-inserts until the app
   * ACKNOWLEDGES the change: the model holds the text AND the header's
   * "Unsaved changes" indicator proves handleChange ran (state caught
   * up, so the next render's sync is a no-op).
   */
  async function setMonacoYaml(page: import('@playwright/test').Page, yaml: string) {
    const editor = page.locator('.monaco-editor').first()
    await editor.click()
    const dirty = page.getByText('Unsaved changes').first()
    for (let attempt = 0; attempt < 20; attempt++) {
      const landed = await page.evaluate((text) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const monaco = (window as any).monaco
        if (!monaco) throw new Error('monaco global not exposed on window')
        const models = monaco.editor.getModels() as Array<{
          getLanguageId: () => string
          getValue: () => string
          setValue: (v: string) => void
        }>
        const yamlModel =
          models.find((m) => m.getLanguageId() === 'yaml') ?? models[0]
        if (!yamlModel) throw new Error('no monaco models present')
        if (yamlModel.getValue() === text) return true
        yamlModel.setValue(text)
        return false
      }, yaml)
      if (landed && (await dirty.isVisible().catch(() => false))) return
      await page.waitForTimeout(250)
    }
    throw new Error('Monaco document never settled on the authored YAML')
  }

  test.afterAll(async ({ api }) => {
    // Best-effort cleanup — workflows are auto-cleaned by test-data teardown
    await api.login(seed.owner.email, seed.owner.password)
    for (const id of [workflowId, yamlWorkflowId]) {
      if (id) {
        try { await deleteWorkflow(api, projectId, id) } catch { /* gone */ }
      }
    }
  })

  // ── WF-CRUD-01: Create workflow via UI ───────────────────────────
  test('WF-CRUD-01: create workflow via UI and see it in the list', async ({ adminPage, api }) => {
    workflowName = `wfcrud-wf-${Date.now().toString(36)}`

    await loginAsOwner(adminPage)

    // Get the actual project name for exact matching in the dropdown
    const projectName = seed.projectName

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

  // ── WF-CRUD-02: Author YAML steps in the editor and save ───────
  test('WF-CRUD-02: author YAML steps in editor and save', async ({ adminPage }) => {
    expect(workflowId).toBeDefined()

    await loginAsOwner(adminPage)

    // Navigate to editor
    await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)
    // Wait for the editor to load
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    await expect(adminPage.getByRole('button', { name: /^Save$/i }).first()).toBeVisible({ timeout: 15_000 })
    // Wait for the status badge
    await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })

    // Replace the editor document with the authored YAML (insertText —
    // verbatim, no auto-indent interference).
    const yamlText = [
      'version: "1.0"',
      'id: "wfcrud"',
      'name: "WF-CRUD"',
      'workflow:',
      '  - id: generate',
      '    name: Generate',
      `    agentProfileCode: ${profileName}`,
      '    prompt: "Review the code and report issues"',
      '    dependsOn: []',
      '  - id: review',
      '    name: Review Step',
      `    agentProfileCode: ${profileName}`,
      '    dependsOn: [generate]',
      '',
    ].join('\n')
    await setMonacoYaml(adminPage, yamlText)

    // Save
    const saveBtn2 = adminPage.getByTestId('workflow-save-button')
    await expect(saveBtn2).toBeEnabled({ timeout: 10_000 })
    await saveBtn2.click()

    // Wait for save to complete — "Unsaved changes" indicator should disappear
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })

    // Reload to verify persistence — the serialized YAML shows the step name
    await adminPage.reload()
    await expect(adminPage.getByText('Review Step').first()).toBeVisible({ timeout: 10_000 })
  })

  // ── WF-CRUD-03: Publish workflow ─────────────────────────────────
  test('WF-CRUD-03: publish workflow → status changes to PUBLISHED', async ({ adminPage }) => {
    expect(workflowId).toBeDefined()

    await loginAsOwner(adminPage)

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

    await loginAsOwner(adminPage)

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
    // We need a published workflow for this test — create one via API as the OWNER
    await api.login(seed.owner.email, seed.owner.password)

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
    await loginAsOwner(adminPage)
    await adminPage.goto(`/workflows/${wf.id}/edit?projectId=${projectId}`)

    // Should show PUBLISHED and v1
    await expect(adminPage.getByText('Failed to load workflow')).not.toBeVisible({ timeout: 15_000 })
    await expect(adminPage.getByText('PUBLISHED').first()).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByText(/v1/i).first()).toBeVisible({ timeout: 10_000 })

    // Add a new step via the YAML surface — replace the document with a
    // two-step definition; save bumps the version (draft v2).
    const yamlText = [
      'version: "1.0"',
      'id: "wfcrud-vtest"',
      'name: "WF-CRUD vtest"',
      'workflow:',
      '  - id: s1',
      '    name: Step 1',
      `    agentProfileCode: ${profileName}`,
      '    dependsOn: []',
      '  - id: s2',
      '    name: Step 2',
      `    agentProfileCode: ${profileName}`,
      '    dependsOn: [s1]',
      '',
    ].join('\n')
    await setMonacoYaml(adminPage, yamlText)

    // Save
    const saveBtn5 = adminPage.getByTestId('workflow-save-button')
    await expect(saveBtn5).toBeEnabled({ timeout: 10_000 })
    await saveBtn5.click()
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })

    // Version should bump to v2 (draft)
    await expect(adminPage.getByText(/v2/i).first()).toBeVisible({ timeout: 10_000 })

    // Cleanup
    try { await deleteWorkflow(api, projectId, wf.id) } catch { /* gone */ }
  })

  // ── WF-CRUD-06: List filtering by status ─────────────────────────
  test('WF-CRUD-06: filter workflows by status', async ({ api, adminPage }) => {
    // Create workflows in different states via API as the OWNER
    await api.login(seed.owner.email, seed.owner.password)

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
    await loginAsOwner(adminPage)
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

  // ── WF-YAML-01: invalid YAML blocks save and shows the issues panel ──
  test('WF-YAML-01: invalid YAML shows issues and disables Save', async ({ api, adminPage }) => {
    // The CRUD tests archived the shared workflow (read-only editor);
    // the YAML authoring tests use their own fresh DRAFT.
    await api.login(seed.owner.email, seed.owner.password)
    const ywf = await api.request<{ id: string }>(
      'POST',
      `/projects/${projectId}/workflows`,
      { projectId, name: `wfyaml-${Date.now().toString(36)}`, steps: [] },
    )
    yamlWorkflowId = ywf.id

    await loginAsOwner(adminPage)
    await adminPage.goto(`/workflows/${ywf.id}/edit?projectId=${projectId}`)
    await expect(adminPage.locator('.monaco-editor').first()).toBeVisible({ timeout: 15_000 })

    await setMonacoYaml(adminPage, 'version: "1.0"\nworkflow: [')

    // The issues panel renders the parse error; Save is disabled
    await expect(adminPage.getByText(/YAML parse/i).first()).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByTestId('workflow-save-button')).toBeDisabled()

    // Restore valid YAML so later serial tests see a sane state
    const yamlText = [
      'version: "1.0"',
      'id: "wfcrud"',
      'name: "WF-CRUD"',
      'workflow:',
      '  - id: generate',
      '    name: Generate',
      `    agentProfileCode: ${profileName}`,
      '    dependsOn: []',
      '',
    ].join('\n')
    await setMonacoYaml(adminPage, yamlText)
    const saveBtn = adminPage.getByTestId('workflow-save-button')
    await expect(saveBtn).toBeEnabled({ timeout: 10_000 })
    await saveBtn.click()
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })
  })

  // ── WF-YAML-02: ORCHESTRATOR YAML saves with bindings ─────────────
  test('WF-YAML-02: ORCHESTRATOR yaml saves with bindings', async ({ api, adminPage }) => {
    expect(yamlWorkflowId).toBeDefined()
    // The orchestration references the seeded active model (004-models
    // changelog: github-gpt-4o) — no admin model plumbing needed, and the
    // public models roster (listActive) carries it for the rule-8 check.
    await api.login(seed.owner.email, seed.owner.password)

    await loginAsOwner(adminPage)
    await adminPage.goto(`/workflows/${yamlWorkflowId}/edit?projectId=${projectId}`)
    await expect(adminPage.locator('.monaco-editor').first()).toBeVisible({ timeout: 15_000 })

    const yamlText = [
      'version: "1.0"',
      'id: "wfcrud"',
      'name: "WF-CRUD"',
      'models: [{ code: "github-gpt-4o" }]',
      'workflow:',
      '  - id: backend',
      '    name: Backend',
      '    taskType: ORCHESTRATOR',
      `    agentProfileCode: ${profileName}`,
      '    dependsOn: []',
      '    retryPolicy: { maxRetries: 1, initialBackoffSeconds: 2, maxBackoffSeconds: 30 }',
      '    orchestration:',
      '      modelCode: "github-gpt-4o"',
      '      goal: "Build it"',
      '      sourceSubPath: "app"',
      '      workers:',
      '        - { name: coder, modelCode: "github-gpt-4o", capability: "Implements", allowedTools: [read_file], allowedCommands: [] }',
      '      checkpointStrategy: { mode: ON_VERIFICATION_PASS, commitMessage: "feat: x", pushToRemote: false, allowNoChanges: false }',
      '      completionCriteria: { definitionOfDone: "done", requireVerificationBy: [coder] }',
      '      budget: { maxTokens: 1000, maxWorkerCalls: 5, maxVerifierRejectionsPerAttempt: 1, maxOrchestratorIterations: 5, maxWorkerIterations: 5, onBudgetExceeded: FAIL }',
      '',
    ].join('\n')
    await setMonacoYaml(adminPage, yamlText)

    const saveBtn = adminPage.getByTestId('workflow-save-button')
    await expect(saveBtn).toBeEnabled({ timeout: 10_000 })
    await saveBtn.click()
    await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })

    // The bindings row landed: alias -> profile UUID
    const wf = await api.request<{ orchestrationBindings?: Record<string, string>; steps: Array<{ taskType?: string }> }>(
      'GET',
      `/projects/${projectId}/workflows/${yamlWorkflowId}`
    )
    expect(wf.orchestrationBindings?.[profileName]).toBe(profileId)
    expect(wf.steps[0]?.taskType).toBe('ORCHESTRATOR')
  })
})