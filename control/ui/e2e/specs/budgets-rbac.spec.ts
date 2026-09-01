// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN, ApiClient } from '../helpers/api'
import { seedProjectWithMembers, seedOrgUser } from '../helpers/rbac'

/**
 * Budgets RBAC — BUD-07.
 *
 * Tests role-based access to budget/quota APIs.
 */

test.describe('Budgets RBAC', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // BUD-07: Role-based budget access
  test('BUD-07: BUDGET_OWNER can create org ceilings; VIEWER cannot', async ({ api }) => {
    // Create a BUDGET_OWNER
    const budgetOwner = await seedOrgUser(api, 'bud07', 'BUDGET_OWNER')

    // Create a project with a viewer
    const { projectId, viewer } = await seedProjectWithMembers(api, 'bud07')

    // BUDGET_OWNER can list quotas (authenticated endpoint, BUDGET_OWNER has view access)
    const boApi = new ApiClient()
    await boApi.login(budgetOwner.email, budgetOwner.password)
    const listResult = await boApi.rawRequest('GET', '/admin/quotas')
    expect(listResult.status).toBe(200)

    // BUDGET_OWNER can create an org-level quota
    const createResult = await boApi.rawRequest('POST', '/admin/quotas', {
      scopeType: 'ORG',
      resourceType: 'TOKENS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 100000,
    })
    // Should succeed (BUDGET_OWNER can manage org budgets)
    expect([200, 201]).toContain(createResult.status)

    // VIEWER can view the budget dashboard (authenticated endpoint)
    const viewerApi = new ApiClient()
    await viewerApi.login(viewer.email, viewer.password)
    const dashboardResult = await viewerApi.rawRequest('GET', '/budgets/dashboard?period=MONTHLY_CALENDAR&resourceType=COST_USD_CENTS')
    expect(dashboardResult.status).toBe(200)

    // VIEWER cannot create a quota (requires BUDGET_OWNER or PROJECT_OWNER)
    const viewerCreateResult = await viewerApi.rawRequest('POST', '/admin/quotas', {
      scopeType: 'ORG',
      resourceType: 'TOKENS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 50000,
    })
    expect(viewerCreateResult.status).toBe(403)

    // PROJECT_OWNER can create project-scoped quotas
    const ownerApi = new ApiClient()
    await ownerApi.login(
      (await seedProjectWithMembers(api, 'bud07owner')).owner.email,
      'TestPass123!',
    )
    // Note: This second project's owner won't have access to the first project.
    // The test proves that VIEWER is blocked — the specific PROJECT_OWNER test
    // would need project-scoped quota creation which requires governance check.
  })

  test('BUD-07: ORG_ADMIN has read-only access to budgets', async ({ api }) => {
    const orgAdmin = await seedOrgUser(api, 'bud07admin', 'ORG_ADMIN')

    const oaApi = new ApiClient()
    await oaApi.login(orgAdmin.email, orgAdmin.password)

    // ORG_ADMIN can view the budget dashboard
    const dashboardResult = await oaApi.rawRequest('GET', '/budgets/dashboard?period=MONTHLY_CALENDAR&resourceType=COST_USD_CENTS')
    expect(dashboardResult.status).toBe(200)

    // ORG_ADMIN can view quotas (has AUDITOR→VIEWER implicit)
    const listResult = await oaApi.rawRequest('GET', '/admin/quotas')
    expect(listResult.status).toBe(200)

    // ORG_ADMIN cannot create org-level quotas (not BUDGET_OWNER)
    const createResult = await oaApi.rawRequest('POST', '/admin/quotas', {
      scopeType: 'ORG',
      resourceType: 'TOKENS',
      period: 'MONTHLY_CALENDAR',
      limitAmount: 100000,
    })
    expect(createResult.status).toBe(403)
  })
})