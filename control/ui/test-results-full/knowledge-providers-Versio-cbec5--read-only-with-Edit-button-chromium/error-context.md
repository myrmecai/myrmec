# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: knowledge-providers.spec.ts >> Versioned Entity standard tests - Knowledge Provider >> VE-S09: Zone 1 starts in View mode (read-only) with [Edit] button
- Location: e2e\helpers\versioned-entity-standard.ts:409:5

# Error details

```
Error: expect(locator).toBeDisabled() failed

Locator: getByRole('button', { name: /Save Draft/i })
Expected: disabled
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeDisabled" with timeout 5000ms
  - waiting for getByRole('button', { name: /Save Draft/i })

```

```yaml
- banner:
  - link "Myrmec Myrmec":
    - /url: /dashboard
    - img "Myrmec"
    - text: Myrmec
  - navigation:
    - link "Dashboard":
      - /url: /dashboard
      - button "Dashboard":
        - img
        - text: Dashboard
    - link "My Work":
      - /url: /my-work
      - button "My Work":
        - img
        - text: My Work
  - button "AD admin@e2e-test.local":
    - text: AD admin@e2e-test.local
    - img
- complementary:
  - text: Navigation
  - button "Collapse sidebar":
    - img
  - link "Dashboard":
    - /url: /dashboard
    - button "Dashboard":
      - img
      - text: Dashboard
  - link "My Work":
    - /url: /my-work
    - button "My Work":
      - img
      - text: My Work
  - navigation:
    - button "Services":
      - img
      - text: Services
      - img
    - button "Budgets":
      - img
      - text: Budgets
      - img
    - button "Platform":
      - img
      - text: Platform
      - img
    - link "AI Infrastructure":
      - /url: /platform/ai-infra/models
      - button "AI Infrastructure":
        - img
        - text: AI Infrastructure
    - link "AI Context":
      - /url: /platform/ai-context/governance-profile
      - button "AI Context":
        - img
        - text: AI Context
    - link "Connections":
      - /url: /platform/connections
      - button "Connections":
        - img
        - text: Connections
    - link "Security & Access":
      - /url: /platform/security/secrets
      - button "Security & Access":
        - img
        - text: Security & Access
    - button "Organization":
      - img
      - text: Organization
      - img
- main:
  - heading "AI Context Management" [level=1]
  - link "Instruction Assets":
    - /url: /platform/ai-context/instruction-assets
    - button "Instruction Assets"
  - link "Knowledge Providers":
    - /url: /platform/ai-context/knowledge-providers
    - button "Knowledge Providers"
  - link "Knowledge Sources":
    - /url: /platform/ai-context/knowledge-sources
    - button "Knowledge Sources"
  - link "Governance Profile":
    - /url: /platform/ai-context/governance-profile
    - button "Governance Profile"
  - link "Knowledge Providers":
    - /url: /platform/ai-context/knowledge-providers
  - text: / ve-viewmode-msyicdxk
  - img
  - heading "ve-viewmode-msyicdxk" [level=1]
  - text: EXTERNAL INCOMPLETE
  - button "Details"
  - button "Knowledge Sources"
  - button "Version History"
  - button "Audit Log"
  - heading "Identity & Metadata" [level=3]
  - button "Edit"
  - term: Name
  - definition: ve-viewmode-msyicdxk
  - term: Type
  - definition: EXTERNAL
  - term: Description
  - definition: —
  - term: Status
  - definition: INCOMPLETE
  - term: Created
  - definition: 8/18/2026, 12:16:19 PM
  - heading "Draft (v1)" [level=3]
  - paragraph: Configure and publish this draft
  - text: Draft Connection Config
  - combobox: Select a connection config
  - text: Max Top-K
  - spinbutton "Max Top-K"
  - text: Similarity Threshold
  - spinbutton "Similarity Threshold"
  - text: Timeout (ms)
  - spinbutton "Timeout (ms)"
  - text: Response Mapping Hits Path
  - textbox "Hits Path":
    - /placeholder: $.results
    - text: $.results
  - text: Passage Path
  - textbox "Passage Path":
    - /placeholder: $.text
    - text: $.text
  - text: Source Name Path
  - textbox "Source Name Path":
    - /placeholder: $.source
    - text: $.source
  - text: Locator Path
  - textbox "Locator Path":
    - /placeholder: $.url
    - text: $.url
  - text: Score Path
  - textbox "Score Path":
    - /placeholder: $.score
  - text: Saved Configuration
  - button "Saving…" [disabled]
  - button "Publish"
  - button "Discard":
    - img
    - text: Discard
- contentinfo:
  - button "Open TanStack Router Devtools":
    - img
    - img
    - text: "- TanStack Router"
```

# Test source

```ts
  1   | // SPDX-License-Identifier: Apache-2.0
  2   | // Copyright 2026 The Myrmec Authors
  3   | 
  4   | import { test, expect } from '../fixtures'
  5   | import type { Page } from '@playwright/test'
  6   | import { E2E_ADMIN } from '../helpers/api'
  7   | import { createVersionedEntityTests, type VersionedEntityConfig } from '../helpers/versioned-entity-standard'
  8   | 
  9   | /**
  10  |  * UC-KM-03 - Manage Org Knowledge Providers
  11  |  *
  12  |  * Single spec file containing:
  13  |  *   1. Standard versioned-entity tests (VE-S01 through VE-S45)
  14  |  *      - Defined in: e2e-testing-standards.md §7
  15  |  *      - Source: governance-and-versioning.md §3, ui-standards.md Patterns 1-7
  16  |  *   2. Entity-specific tests (T-KP-01 through T-KP-12)
  17  |  *      - See: UC-KM-03-manage-org-knowledge-providers.md §13
  18  |  *
  19  |  * Strategy: Drive the UI for all interactions (per e2e-testing-standards.md).
  20  |  * API is used ONLY for login, cleanup, and cross-UC setup.
  21  |  *
  22  |  * Key differences from Instruction Assets (UC-KM-02):
  23  |  *   - Quick-Create auto-creates a Draft and navigates to detail page (UC-KM-03 Step 3)
  24  |  *   - Zone 1 IS editable (Name, Description) — UC-KM-03 Step 5
  25  |  *   - Type is immutable after creation (External only for initial release)
  26  |  *   - Zone 2 fields: Connection Config, Max Top-K, Similarity Threshold,
  27  |  *     Response Mapping (5 JSONPath fields), Timeout
  28  |  *   - Knowledge Sources section: Add/Edit via dialog, Delete inline with confirmation
  29  |  *   - Sources are locked to the version — editable only when a Draft exists
  30  |  */
  31  | 
  32  | const BASE = '/admin/knowledge-providers'
  33  | 
  34  | // === Helper functions ===
  35  | 
  36  | async function cleanupProvider(api: any, providerId: string): Promise<void> {
  37  |   try { await api.request('POST', `${BASE}/${providerId}/disable`) } catch { /* ignore */ }
  38  |   try { await api.request('POST', `${BASE}/${providerId}/archive`) } catch { /* ignore */ }
  39  |   try { await api.request('DELETE', `${BASE}/${providerId}`) } catch { /* gone */ }
  40  | }
  41  | 
  42  | async function findProviderByName(api: any, name: string): Promise<string | null> {
  43  |   const providers = await api.request('GET', BASE)
  44  |   const found = (providers as any[]).find((p) => p.name === name)
  45  |   return found?.id ?? null
  46  | }
  47  | 
  48  | /**
  49  |  * Custom create-and-publish flow for Knowledge Providers:
  50  |  * 1. Quick-Create (Name + Type) → auto-navigates to detail with Draft created
  51  |  * 2. Fill Zone 2 config fields, save draft, publish
  52  |  */
  53  | async function createProviderAndPublish(page: Page, _config: VersionedEntityConfig, name: string): Promise<void> {
  54  |   await page.goto('/platform/ai-context/knowledge-providers')
  55  |   await page.getByRole('button', { name: 'New Provider' }).click()
  56  |   await page.getByLabel('Name').fill(name)
  57  |   await page.getByRole('button', { name: /Create/i }).click()
  58  |   // Auto-navigates to detail page with Draft already created (Pattern 4 §Rule 4)
  59  |   await expect(page).toHaveURL(/\/platform\/ai-context\/knowledge-providers\/[^/]+$/, { timeout: 10_000 })
  60  |   await expect(page.getByText('Draft (v1)', { exact: false })).toBeVisible({ timeout: 10_000 })
  61  | 
  62  |   // Fill required Zone 2 fields
  63  |   await page.getByLabel('Hits Path').fill('$.results')
  64  |   await page.getByLabel('Passage Path').fill('$.text')
  65  |   await page.getByLabel('Source Name Path').fill('$.source')
  66  |   await page.getByLabel('Locator Path').fill('$.url')
  67  | 
  68  |   // Save Draft
  69  |   const saveBtn = page.getByRole('button', { name: /Save Draft/i })
  70  |   await expect(saveBtn).toBeEnabled({ timeout: 5_000 })
  71  |   await saveBtn.click()
> 72  |   await expect(saveBtn).toBeDisabled({ timeout: 5_000 })
      |                         ^ Error: expect(locator).toBeDisabled() failed
  73  | 
  74  |   // Publish
  75  |   await page.getByRole('button', { name: /Publish/i }).click()
  76  |   await expect(page.getByText(/Published Version/i)).toBeVisible({ timeout: 10_000 })
  77  | }
  78  | 
  79  | /**
  80  |  * Custom fill Draft fields and save.
  81  |  * Fills the required response mapping fields so the publish gate passes.
  82  |  */
  83  | async function fillProviderDraftAndSave(page: Page, _config: VersionedEntityConfig): Promise<void> {
  84  |   const hitsInput = page.getByLabel('Hits Path')
  85  |   if (await hitsInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
  86  |     await hitsInput.fill('$.results')
  87  |   }
  88  |   const passageInput = page.getByLabel('Passage Path')
  89  |   if (await passageInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
  90  |     await passageInput.fill('$.text')
  91  |   }
  92  |   const sourceNameInput = page.getByLabel('Source Name Path')
  93  |   if (await sourceNameInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
  94  |     await sourceNameInput.fill('$.source')
  95  |   }
  96  |   const locatorInput = page.getByLabel('Locator Path')
  97  |   if (await locatorInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
  98  |     await locatorInput.fill('$.url')
  99  |   }
  100 |   const saveBtn = page.getByRole('button', { name: /Save Draft/i })
  101 |   if (await saveBtn.isEnabled({ timeout: 3_000 }).catch(() => false)) {
  102 |     await saveBtn.click()
  103 |     await expect(saveBtn).toBeDisabled({ timeout: 5_000 })
  104 |   }
  105 | }
  106 | 
  107 | // === Standard Versioned Entity Tests ===
  108 | createVersionedEntityTests({
  109 |   entityName: 'Knowledge Provider',
  110 |   listRoute: '/platform/ai-context/knowledge-providers',
  111 |   detailRoutePattern: /\/platform\/ai-context\/knowledge-providers\/[^/]+$/,
  112 |   apiBase: BASE,
  113 |   listHeading: 'Knowledge Providers',
  114 |   listCardTitle: 'Organization-Scoped Providers',
  115 |   createButtonText: 'New Provider',
  116 |   createDialogHeading: 'Create Knowledge Provider',
  117 |   nameLabel: 'Name',
  118 |   // Knowledge Providers don't have a URL field — use custom fill
  119 |   draftUrlLabel: undefined,
  120 |   draftUrlValue: undefined,
  121 |   draftUrlValueV2: undefined,
  122 |   // Quick-Create auto-creates a Draft and navigates to detail page (Pattern 4 §Rule 4)
  123 |   autoCreateDraft: true,
  124 |   // Zone 1 is editable
  125 |   zone1Editable: true,
  126 |   // Publish gate validates required fields (connection config, response mapping paths)
  127 |   hasPublishGate: true,
  128 |   // Custom create-and-publish flow
  129 |   customCreateDraftAndPublish: createProviderAndPublish,
  130 |   customFillDraftAndSave: fillProviderDraftAndSave,
  131 |   adminEmail: E2E_ADMIN.email,
  132 |   adminPassword: E2E_ADMIN.password,
  133 | })
  134 | 
  135 | // === Entity-Specific Tests ===
  136 | 
  137 | test.describe('UC-KM-03 knowledge providers - entity-specific tests', () => {
  138 |   test.beforeEach(async ({ api }) => {
  139 |     await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  140 |   })
  141 | 
  142 |   // T-KP-01: Quick-create dialog shows Name + Type (2 fields)
  143 |   test('T-KP-01: Quick-create dialog shows Name and Type', async ({ adminPage, api }) => {
  144 |     const name = `e2e-kp-dialog-${Date.now().toString(36)}`
  145 |     try {
  146 |       await adminPage.goto('/platform/ai-context/knowledge-providers')
  147 |       await adminPage.getByRole('button', { name: 'New Provider' }).click()
  148 |       await expect(adminPage.getByRole('heading', { name: 'Create Knowledge Provider' })).toBeVisible()
  149 |       await expect(adminPage.getByLabel('Name')).toBeVisible()
  150 |       // Type dropdown should be visible
  151 |       await expect(adminPage.locator('label[for="type"]')).toBeVisible()
  152 |       // [Create] should be disabled until Name filled
  153 |       const createButton = adminPage.getByRole('button', { name: /Create/i })
  154 |       await expect(createButton).toBeDisabled()
  155 |       await adminPage.getByLabel('Name').fill(name)
  156 |       await expect(createButton).toBeEnabled()
  157 |     } finally {
  158 |       const id = await findProviderByName(api, name)
  159 |       if (id) await cleanupProvider(api, id)
  160 |     }
  161 |   })
  162 | 
  163 |   // T-KP-02: Type dropdown shows External
  164 |   test('T-KP-02: Type dropdown shows External', async ({ adminPage, api }) => {
  165 |     const name = `e2e-kp-type-${Date.now().toString(36)}`
  166 |     try {
  167 |       await adminPage.goto('/platform/ai-context/knowledge-providers')
  168 |       await adminPage.getByRole('button', { name: 'New Provider' }).click()
  169 |       await expect(adminPage.getByRole('heading', { name: 'Create Knowledge Provider' })).toBeVisible()
  170 | 
  171 |       // Open the Type dropdown
  172 |       await adminPage.locator('label[for="type"]').locator('..').locator('button').first().click()
```