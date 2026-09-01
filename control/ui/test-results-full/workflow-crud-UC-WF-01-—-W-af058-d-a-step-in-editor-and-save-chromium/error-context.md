# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-crud.spec.ts >> UC-WF-01 — Workflow CRUD >> WF-CRUD-02: add a step in editor and save
- Location: e2e\specs\workflow-crud.spec.ts:104:3

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: locator.click: Test timeout of 30000ms exceeded.
Call log:
  - waiting for getByPlaceholder('Select profile...')

```

# Test source

```ts
  27  | import { seedProjectWithMembers } from '../helpers/rbac'
  28  | import { loginAsAdmin } from '../helpers/auth'
  29  | import {
  30  |   createAgentProfileForWorkflow,
  31  |   deleteWorkflow,
  32  | } from '../helpers/workflow'
  33  | 
  34  | const WORKFLOWS_URL = '/workflows'
  35  | 
  36  | test.describe('UC-WF-01 — Workflow CRUD', () => {
  37  |   test.describe.configure({ mode: 'serial' })
  38  | 
  39  |   let projectId: string
  40  |   let profileId: string
  41  |   let profileName: string
  42  |   let workflowName: string
  43  |   let workflowId: string
  44  | 
  45  |   test.beforeAll(async ({ api }) => {
  46  |     await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  47  |     const seed = await seedProjectWithMembers(api, 'wfcrud', {
  48  |       allowedServiceTypes: ['WORKFLOW'],
  49  |     })
  50  |     projectId = seed.projectId
  51  | 
  52  |     profileName = `wfcrud-profile-${Date.now().toString(36)}`
  53  |     profileId = await createAgentProfileForWorkflow(api, profileName)
  54  |   })
  55  | 
  56  |   test.afterAll(async ({ api }) => {
  57  |     // Best-effort cleanup — workflows are auto-cleaned by test-data teardown
  58  |     if (workflowId) {
  59  |       await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  60  |       try { await deleteWorkflow(api, projectId, workflowId) } catch { /* gone */ }
  61  |     }
  62  |   })
  63  | 
  64  |   // ── WF-CRUD-01: Create workflow via UI ───────────────────────────
  65  |   test('WF-CRUD-01: create workflow via UI and see it in the list', async ({ adminPage }) => {
  66  |     workflowName = `wfcrud-wf-${Date.now().toString(36)}`
  67  | 
  68  |     await loginAsAdmin(adminPage)
  69  |     await adminPage.goto(WORKFLOWS_URL)
  70  | 
  71  |     // Click "New Workflow"
  72  |     await adminPage.getByRole('button', { name: /New Workflow/i }).click()
  73  | 
  74  |     // Fill create dialog
  75  |     await adminPage.getByPlaceholder('e.g., Code Review Pipeline').fill(workflowName)
  76  |     await adminPage.getByPlaceholder('What does this workflow do?').fill('Test workflow for CRUD spec')
  77  | 
  78  |     // Select project
  79  |     await adminPage.getByRole('combobox').first().click()
  80  |     // Wait for the select dropdown and pick the seeded project
  81  |     await adminPage.getByRole('option', { name: /wfcrud/i }).first().click()
  82  | 
  83  |     // Submit
  84  |     await adminPage.getByRole('button', { name: /Create & open editor/i }).click()
  85  | 
  86  |     // Should navigate to the editor
  87  |     await expect(adminPage).toHaveURL(/\/workflows\/[^/]+\/edit/, { timeout: 15_000 })
  88  | 
  89  |     // Extract workflowId from URL
  90  |     const url = adminPage.url()
  91  |     const match = url.match(/\/workflows\/([^/]+)\/edit/)
  92  |     expect(match).not.toBeNull()
  93  |     workflowId = match![1]
  94  | 
  95  |     // Editor should show DRAFT status
  96  |     await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })
  97  | 
  98  |     // Go back to list and verify it appears
  99  |     await adminPage.goto(WORKFLOWS_URL)
  100 |     await expect(adminPage.getByText(workflowName).first()).toBeVisible({ timeout: 10_000 })
  101 |   })
  102 | 
  103 |   // ── WF-CRUD-02: Add a step in the editor and save ────────────────
  104 |   test('WF-CRUD-02: add a step in editor and save', async ({ adminPage }) => {
  105 |     expect(workflowId).toBeDefined()
  106 |     await loginAsAdmin(adminPage)
  107 | 
  108 |     // Navigate to editor
  109 |     await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)
  110 |     // Wait for the editor to load — the Save button appears once the workflow data is loaded
  111 |     await expect(adminPage.getByRole('button', { name: /Save/i })).toBeVisible({ timeout: 15_000 })
  112 |     // Wait for the status badge
  113 |     await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })
  114 | 
  115 |     // Click "Add Step" on the canvas
  116 |     await adminPage.getByTestId('add-step-button').click()
  117 | 
  118 |     // A new step should appear in the left rail and the properties panel
  119 |     // The default name is "New Step 1"
  120 |     await expect(adminPage.getByText(/New Step 1/i).first()).toBeVisible({ timeout: 5_000 })
  121 | 
  122 |     // Fill the Name field in Step Properties (2nd input in the basic info section)
  123 |     const nameField = adminPage.locator('.space-y-3 > div').nth(1).locator('input')
  124 |     await nameField.fill('Review Step')
  125 | 
  126 |     // Select agent profile from dropdown
> 127 |     await adminPage.getByPlaceholder('Select profile...').click()
      |                                                           ^ Error: locator.click: Test timeout of 30000ms exceeded.
  128 |     await adminPage.getByRole('option', { name: profileName }).click()
  129 | 
  130 |     // Fill prompt — Monaco editor, click and type
  131 |     const monacoEditor = adminPage.locator('.monaco-editor').first()
  132 |     await monacoEditor.click()
  133 |     await adminPage.keyboard.type('Review the code and report issues')
  134 | 
  135 |     // Save
  136 |     await adminPage.getByRole('button', { name: /Save/i }).click()
  137 | 
  138 |     // Wait for save to complete — "Unsaved changes" indicator should disappear
  139 |     await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })
  140 | 
  141 |     // Reload to verify persistence
  142 |     await adminPage.reload()
  143 |     await expect(adminPage.getByText('Review Step').first()).toBeVisible({ timeout: 10_000 })
  144 |   })
  145 | 
  146 |   // ── WF-CRUD-03: Publish workflow ─────────────────────────────────
  147 |   test('WF-CRUD-03: publish workflow → status changes to PUBLISHED', async ({ adminPage }) => {
  148 |     expect(workflowId).toBeDefined()
  149 |     await loginAsAdmin(adminPage)
  150 | 
  151 |     await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)
  152 | 
  153 |     // Wait for editor to load
  154 |     await expect(adminPage.getByRole('button', { name: /Save/i })).toBeVisible({ timeout: 15_000 })
  155 |     // Ensure we're in DRAFT state
  156 |     await expect(adminPage.getByText('DRAFT').first()).toBeVisible({ timeout: 10_000 })
  157 | 
  158 |     // Click Publish
  159 |     await adminPage.getByTestId('workflow-publish-button').click()
  160 | 
  161 |     // Status should change to PUBLISHED
  162 |     await expect(adminPage.getByText('PUBLISHED').first()).toBeVisible({ timeout: 10_000 })
  163 | 
  164 |     // Verify in list view
  165 |     await adminPage.goto(WORKFLOWS_URL)
  166 |     await expect(adminPage.getByText(workflowName).first()).toBeVisible({ timeout: 10_000 })
  167 |   })
  168 | 
  169 |   // ── WF-CRUD-04: Archive workflow ─────────────────────────────────
  170 |   test('WF-CRUD-04: archive workflow → status changes to ARCHIVED', async ({ adminPage }) => {
  171 |     expect(workflowId).toBeDefined()
  172 |     await loginAsAdmin(adminPage)
  173 | 
  174 |     await adminPage.goto(`/workflows/${workflowId}/edit?projectId=${projectId}`)
  175 | 
  176 |     // Wait for editor to load
  177 |     await expect(adminPage.getByRole('button', { name: /Save/i }).first()).toBeVisible({ timeout: 15_000 })
  178 | 
  179 |     // Click Archive
  180 |     await adminPage.getByRole('button', { name: /Archive/i }).click()
  181 | 
  182 |     // Status should change to ARCHIVED
  183 |     await expect(adminPage.getByText('ARCHIVED').first()).toBeVisible({ timeout: 10_000 })
  184 |   })
  185 | 
  186 |   // ── WF-CRUD-05: Edit published workflow creates new draft version ─
  187 |   test('WF-CRUD-05: editing a published workflow via API then UI shows version bump', async ({ api, adminPage }) => {
  188 |     // We need a published workflow for this test — create one via API
  189 |     await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  190 | 
  191 |     const draftName = `wfcrud-vtest-${Date.now().toString(36)}`
  192 |     const wf = await api.request<{ id: string; version: number }>(
  193 |       'POST',
  194 |       `/projects/${projectId}/workflows`,
  195 |       {
  196 |         projectId,
  197 |         name: draftName,
  198 |         steps: [{
  199 |           id: 's1',
  200 |           name: 'Step 1',
  201 |           agentProfileId: profileId,
  202 |           prompt: 'Initial step',
  203 |           dependsOn: [],
  204 |           transitions: {},
  205 |           timeoutSeconds: 300,
  206 |           maxRetries: 0,
  207 |           pauseMode: 'NONE',
  208 |         }],
  209 |       },
  210 |     )
  211 |     await api.request('POST', `/projects/${projectId}/workflows/${wf.id}/publish`)
  212 | 
  213 |     // Now open the editor in the UI
  214 |     await loginAsAdmin(adminPage)
  215 |     await adminPage.goto(`/workflows/${wf.id}/edit?projectId=${projectId}`)
  216 | 
  217 |     // Should show PUBLISHED and v1
  218 |     await expect(adminPage.getByText('PUBLISHED').first()).toBeVisible({ timeout: 10_000 })
  219 |     await expect(adminPage.getByText(/v1/i).first()).toBeVisible({ timeout: 10_000 })
  220 | 
  221 |     // Add a new step — this should create a draft version
  222 |     await adminPage.getByTestId('add-step-button').click()
  223 |     await expect(adminPage.getByText(/New Step/i).first()).toBeVisible({ timeout: 5_000 })
  224 | 
  225 |     // Save
  226 |     await adminPage.getByRole('button', { name: /Save/i }).click()
  227 |     await expect(adminPage.getByText('Unsaved changes')).toBeHidden({ timeout: 10_000 })
```