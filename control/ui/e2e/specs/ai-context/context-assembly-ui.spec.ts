// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * CTX-14 — UI: effective context preview shows resolved stack with token counts.
 *
 * Opens the Effective Context Preview page and asserts the resolved stack
 * and token counts match the API preview.
 */

import { test, expect } from '../../fixtures'
import { E2E_ADMIN } from '../../helpers/api'
import {
  createOrgInstructionAsset,
  useFlexibleGovernance,
  cleanupAsset,
  getContextPreview,
} from '../../helpers/context-assembly'
import { resetGovernanceProfile } from '../../helpers/governance'

test.describe('CTX-14 — effective context preview UI', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await useFlexibleGovernance(api)
  })

  test.afterEach(async ({ api }) => {
    await resetGovernanceProfile(api)
  })

  test('UI shows the same resolved stack as the context-preview endpoint', async ({
    api,
    adminPage,
  }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-14-${Date.now().toString(36)}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx14-ui-${Date.now().toString(36)}`)

    try {
      // Get API preview for comparison
      const apiPreview = await getContextPreview(api, project.id)
      expect(apiPreview.instructions.length).toBeGreaterThan(0)

      // Navigate to the UI preview page
      await adminPage.goto(`/projects/${project.id}/ai-context-preview`)

      // Page heading
      await expect(
        adminPage.getByRole('heading', { name: 'Effective Context Preview' }),
      ).toBeVisible()

      // Token budget card is visible
      await expect(adminPage.getByTestId('token-budget-card')).toBeVisible()
      await expect(adminPage.getByTestId('token-budget-used')).toContainText(
        String(apiPreview.totalTokens),
      )

      // Each instruction from the API should appear in the UI
      for (let i = 0; i < apiPreview.instructions.length; i++) {
        const apiInstr = apiPreview.instructions[i]
        const uiRow = adminPage.getByTestId(`context-instruction-${i}`)
        await expect(uiRow).toBeVisible()
        await expect(uiRow).toContainText(apiInstr.name)
        await expect(adminPage.getByTestId(`instruction-tokens-${i}`)).toContainText(
          String(apiInstr.tokenCount),
        )
      }

      // Governance metadata
      await expect(
        adminPage.getByText(apiPreview.governanceProfileCode),
      ).toBeVisible()
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})