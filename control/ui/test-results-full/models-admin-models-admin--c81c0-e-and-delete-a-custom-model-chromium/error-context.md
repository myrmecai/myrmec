# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: models-admin.spec.ts >> models admin >> admin can create and delete a custom model
- Location: e2e\specs\models-admin.spec.ts:30:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Page snapshot

```yaml
- generic [ref=e2]:
  - generic [ref=e3]:
    - banner [ref=e4]:
      - link "Myrmec Myrmec" [ref=e5] [cursor=pointer]:
        - /url: /dashboard
        - img "Myrmec" [ref=e6]
        - generic [ref=e7]: Myrmec
      - navigation [ref=e8]:
        - link "Dashboard" [ref=e9] [cursor=pointer]:
          - /url: /dashboard
          - button "Dashboard" [ref=e10]:
            - img [ref=e11]
            - text: Dashboard
        - link "My Work" [ref=e16] [cursor=pointer]:
          - /url: /my-work
          - button "My Work" [ref=e17]:
            - img [ref=e18]
            - text: My Work
      - button "AD admin@e2e-test.local" [ref=e21] [cursor=pointer]:
        - generic [ref=e23]: AD
        - generic [ref=e24]: admin@e2e-test.local
        - img [ref=e25]
    - generic [ref=e27]:
      - complementary [ref=e28]:
        - generic [ref=e29]:
          - generic [ref=e30]: Navigation
          - button "Collapse sidebar" [ref=e31] [cursor=pointer]:
            - img [ref=e32]
        - generic [ref=e35]:
          - link "Dashboard" [ref=e36] [cursor=pointer]:
            - /url: /dashboard
            - button "Dashboard" [ref=e37]:
              - img [ref=e38]
              - text: Dashboard
          - link "My Work" [ref=e43] [cursor=pointer]:
            - /url: /my-work
            - button "My Work" [ref=e44]:
              - img [ref=e45]
              - text: My Work
        - navigation [ref=e49]:
          - button "Services" [ref=e51] [cursor=pointer]:
            - img [ref=e52]
            - generic [ref=e56]: Services
            - img [ref=e57]
          - button "Budgets" [ref=e60] [cursor=pointer]:
            - img [ref=e61]
            - generic [ref=e64]: Budgets
            - img [ref=e65]
          - generic [ref=e67]:
            - button "Platform" [ref=e68] [cursor=pointer]:
              - img [ref=e69]
              - generic [ref=e72]: Platform
              - img [ref=e73]
            - generic [ref=e75]:
              - link "AI Infrastructure" [ref=e76] [cursor=pointer]:
                - /url: /platform/ai-infra/models
                - button "AI Infrastructure" [ref=e77]:
                  - img [ref=e78]
                  - text: AI Infrastructure
              - link "AI Context" [ref=e81] [cursor=pointer]:
                - /url: /platform/ai-context/governance-profile
                - button "AI Context" [ref=e82]:
                  - img [ref=e83]
                  - text: AI Context
              - link "Connections" [ref=e95] [cursor=pointer]:
                - /url: /platform/connections
                - button "Connections" [ref=e96]:
                  - img [ref=e97]
                  - text: Connections
              - link "Security & Access" [ref=e100] [cursor=pointer]:
                - /url: /platform/security/secrets
                - button "Security & Access" [ref=e101]:
                  - img [ref=e102]
                  - text: Security & Access
          - button "Organization" [ref=e106] [cursor=pointer]:
            - img [ref=e107]
            - generic [ref=e111]: Organization
            - img [ref=e112]
      - main [ref=e114]:
        - generic [ref=e115]:
          - generic [ref=e116]:
            - heading "AI Infrastructure" [level=1] [ref=e117]
            - generic [ref=e118]:
              - link "Models" [ref=e119] [cursor=pointer]:
                - /url: /platform/ai-infra/models
                - button "Models" [ref=e120]
              - link "Model Providers" [ref=e121] [cursor=pointer]:
                - /url: /platform/ai-infra/providers
                - button "Model Providers" [ref=e122]
              - link "Agent Profiles" [ref=e123] [cursor=pointer]:
                - /url: /platform/ai-infra/agent-profiles
                - button "Agent Profiles" [ref=e124]
              - link "Agents" [ref=e125] [cursor=pointer]:
                - /url: /platform/ai-infra/agents
                - button "Agents" [ref=e126]
              - link "Tools" [ref=e127] [cursor=pointer]:
                - /url: /platform/ai-infra/tools
                - button "Tools" [ref=e128]
          - generic [ref=e130]:
            - generic [ref=e131]:
              - generic [ref=e132]:
                - heading "Models" [level=1] [ref=e133]
                - paragraph [ref=e134]: Manage AI model configurations
              - button "New Model" [ref=e135] [cursor=pointer]:
                - img [ref=e136]
                - text: New Model
            - generic [ref=e137]:
              - generic [ref=e138]:
                - heading "All Models" [level=3] [ref=e139]
                - paragraph [ref=e140]: 2 models configured
              - generic [ref=e142]:
                - table [ref=e144]:
                  - rowgroup [ref=e145]:
                    - row "Code Name Provider Model ID Type Status Actions" [ref=e146]:
                      - columnheader "Code" [ref=e147]:
                        - generic [ref=e148]:
                          - text: Code
                          - img [ref=e150] [cursor=pointer]
                      - columnheader "Name" [ref=e152]:
                        - generic [ref=e153]:
                          - text: Name
                          - img [ref=e155] [cursor=pointer]
                      - columnheader "Provider" [ref=e157]
                      - columnheader "Model ID" [ref=e158]
                      - columnheader "Type" [ref=e159]
                      - columnheader "Status" [ref=e160]:
                        - generic [ref=e161]:
                          - text: Status
                          - img [ref=e163] [cursor=pointer]
                      - columnheader "Actions" [ref=e165]
                  - rowgroup [ref=e166]:
                    - row "github-gpt-4o GPT-4o (GitHub Models) GitHub Models gpt-4o Cloud Active" [ref=e167]:
                      - cell "github-gpt-4o" [ref=e168]
                      - cell "GPT-4o (GitHub Models)" [ref=e169]
                      - cell "GitHub Models" [ref=e170]
                      - cell "gpt-4o" [ref=e171]:
                        - generic [ref=e172]: gpt-4o
                      - cell "Cloud" [ref=e173]:
                        - generic [ref=e174]:
                          - img [ref=e175]
                          - text: Cloud
                      - cell "Active" [ref=e177]:
                        - generic [ref=e179]:
                          - img [ref=e180]
                          - text: Active
                      - cell [ref=e183]:
                        - generic [ref=e184]:
                          - button "Test connection" [ref=e185] [cursor=pointer]:
                            - img [ref=e186]
                          - button "Edit" [ref=e188] [cursor=pointer]:
                            - img [ref=e189]
                          - button "Delete" [ref=e192] [cursor=pointer]:
                            - img [ref=e193]
                    - row "e2e-model-msyig2hm E2E Test Model OpenAI test-model-id Cloud Active" [ref=e196]:
                      - cell "e2e-model-msyig2hm" [ref=e197]
                      - cell "E2E Test Model" [ref=e198]
                      - cell "OpenAI" [ref=e199]
                      - cell "test-model-id" [ref=e200]:
                        - generic [ref=e201]: test-model-id
                      - cell "Cloud" [ref=e202]:
                        - generic [ref=e203]:
                          - img [ref=e204]
                          - text: Cloud
                      - cell "Active" [ref=e206]:
                        - generic [ref=e208]:
                          - img [ref=e209]
                          - text: Active
                      - cell [ref=e212]:
                        - generic [ref=e213]:
                          - button "Test connection" [ref=e214] [cursor=pointer]:
                            - img [ref=e215]
                          - button "Edit" [ref=e217] [cursor=pointer]:
                            - img [ref=e218]
                          - button "Delete" [ref=e221] [cursor=pointer]:
                            - img [ref=e222]
                - generic [ref=e225]:
                  - generic [ref=e226]: 0 of 2 selected
                  - generic [ref=e228]:
                    - generic [ref=e229]:
                      - paragraph [ref=e230]: Rows per page
                      - combobox [ref=e231] [cursor=pointer]:
                        - generic: "10"
                        - img [ref=e232]
                    - generic [ref=e234]: Page 1 of 1
                    - generic [ref=e235]:
                      - button "Go to first page" [disabled]:
                        - generic: Go to first page
                        - img
                      - button "Go to previous page" [disabled]:
                        - generic: Go to previous page
                        - img
                      - button "Go to next page" [disabled]:
                        - generic: Go to next page
                        - img
                      - button "Go to last page" [disabled]:
                        - generic: Go to last page
                        - img
  - generic:
    - contentinfo:
      - button "Open TanStack Router Devtools" [ref=e236] [cursor=pointer]:
        - generic [ref=e237]:
          - img [ref=e239]
          - img [ref=e274]
        - generic [ref=e308]: "-"
        - generic [ref=e309]: TanStack Router
```

# Test source

```ts
  1  | import { test, expect } from '../fixtures'
  2  | import { E2E_ADMIN } from '../helpers/api'
  3  | import { confirmDialog } from '../helpers/confirm-dialog'
  4  | 
  5  | /**
  6  |  * Models admin — list, create, edit, delete.
  7  |  *
  8  |  * Verifies the models table renders seeded models, a new model can be
  9  |  * created via the dialog form, and a non-seeded model can be deleted.
  10 |  * System-seeded models (github-gpt-4o) should not be deletable.
  11 |  */
  12 | test.describe('models admin', () => {
  13 |   test.beforeEach(async ({ api }) => {
  14 |     await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  15 |   })
  16 | 
  17 |   test('models list renders seeded models', async ({ adminPage }) => {
  18 |     await adminPage.goto('/platform/ai-infra/models')
  19 | 
  20 |     await expect(
  21 |       adminPage.getByRole('heading', { name: 'Models', exact: true }),
  22 |     ).toBeVisible()
  23 | 
  24 |     // The seeded model should be present
  25 |     await expect(
  26 |       adminPage.getByText('github-gpt-4o').first(),
  27 |     ).toBeVisible()
  28 |   })
  29 | 
  30 |   test('admin can create and delete a custom model', async ({ adminPage }) => {
  31 |     const code = `e2e-model-${Date.now().toString(36)}`
  32 | 
  33 |     await adminPage.goto('/platform/ai-infra/models')
  34 | 
  35 |     // Open the create dialog
  36 |     await adminPage.getByRole('button', { name: 'New Model' }).click()
  37 |     await expect(
  38 |       adminPage.getByRole('heading', { name: 'New Model' }),
  39 |     ).toBeVisible()
  40 | 
  41 |     // Fill the form — Cloud deployment, use the first available provider
  42 |     await adminPage.getByLabel('Model Code').fill(code)
  43 |     await adminPage.getByLabel('Display Name').fill('E2E Test Model')
  44 |     await adminPage.getByLabel('Model ID').fill('test-model-id')
  45 |     // API Key is required for providers with requiresAuth=true
  46 |     await adminPage.getByLabel('API Key').fill('e2e-test-key')
  47 | 
  48 |     // Submit
  49 |     await adminPage.getByRole('button', { name: 'Create Model' }).click()
  50 | 
  51 |     // Wait for the dialog to close
  52 |     await expect(adminPage.getByRole('heading', { name: 'New Model' })).not.toBeVisible({ timeout: 10_000 })
  53 | 
  54 |     // Reload to ensure the table refreshes
  55 |     await adminPage.reload()
  56 | 
  57 |     // The new model should appear in the table — search across pages if needed
  58 |     const codeRegex = new RegExp(code)
  59 |     let found = false
  60 |     for (let page = 0; page < 5; page++) {
  61 |       if (await adminPage.getByRole('row', { name: codeRegex }).isVisible({ timeout: 3_000 }).catch(() => false)) {
  62 |         found = true
  63 |         break
  64 |       }
  65 |       const nextButton = adminPage.getByRole('button', { name: /next page|next/i })
  66 |       if (await nextButton.isEnabled({ timeout: 1_000 }).catch(() => false)) {
  67 |         await nextButton.click()
  68 |         await adminPage.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => {})
  69 |       } else {
  70 |         break
  71 |       }
  72 |     }
> 73 |     expect(found).toBe(true)
     |                   ^ Error: expect(received).toBe(expected) // Object.is equality
  74 |     const row = adminPage.getByRole('row', { name: codeRegex })
  75 | 
  76 |     // Delete the model
  77 |     await row.getByRole('button', { name: 'Delete' }).click()
  78 |     await confirmDialog(adminPage, 'Delete')
  79 | 
  80 |     await expect(row).toHaveCount(0, { timeout: 10_000 })
  81 |   })
  82 | })
```