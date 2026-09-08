import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

test.describe('My Work approvals', () => {
  test('rejects an approval with a comment from the My Work queue', async ({
    api,
    adminPage,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `my-work-approval-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>(
      'POST',
      '/conversations',
      {
        projectId: project.id,
        title: 'my work approval spec',
      },
    )

    let posted: { decision: string; comment: string | null } | null = null

    await adminPage.route('**/api/v1/my-work/summary*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          workflows: { active: 0, created: 0 },
          conversations: { active: 0, created: 0 },
          approvalsPending: 1,
          archivedCount: 0,
          firstRun: false,
          canCreateWorkflow: false,
          canCreateConversation: false,
        }),
      })
    })

    await adminPage.route('**/api/v1/my-work/approvals*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            messageId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
            conversationId: conversation.id,
            projectId: project.id,
            projectName: 'My Work Approval Project',
            source: 'CONVERSATION',
            assistantId: null,
            summary: 'Approve the plan before execution',
            payloadJson: JSON.stringify({
              kind: 'sql',
              sql: 'SELECT 1',
            }),
            requestedByUserId: null,
            externalUserRef: null,
            requestedAt: new Date().toISOString(),
            expiresAt: null,
          },
        ]),
      })
    })

    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/approvals/*`,
      async (route, request) => {
        posted = request.postDataJSON() as {
          decision: string
          comment: string | null
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        })
      },
    )

    await adminPage.goto('/my-work')
    await adminPage.getByRole('tab', { name: 'Approvals' }).click()

    await expect(adminPage.getByText('Approve the plan before execution')).toBeVisible()
    await adminPage.getByRole('button', { name: 'Reject with comment' }).click()
    await adminPage.getByPlaceholder('Reason for rejection').fill('Needs more review')
    await adminPage
      .getByRole('dialog')
      .getByRole('button', { name: 'Reject with comment' })
      .click()

    await expect.poll(() => posted, { timeout: 5_000 }).not.toBeNull()
    expect(posted?.decision).toBe('REJECTED')
    expect(posted?.comment).toBe('Needs more review')
  })

  test('approves a workflow EXECUTION approval through the unified approvals endpoint (§17.4)', async ({
    api,
    adminPage,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `my-work-orch-${Date.now()}`,
    })
    const taskId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

    let posted: { decision: string; comment: string | null } | null = null

    await adminPage.route('**/api/v1/my-work/summary*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          workflows: { active: 0, created: 0 },
          conversations: { active: 0, created: 0 },
          approvalsPending: 1,
          archivedCount: 0,
          firstRun: false,
          canCreateWorkflow: false,
          canCreateConversation: false,
        }),
      })
    })

    await adminPage.route('**/api/v1/my-work/approvals*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            messageId: taskId,
            conversationId: null,
            projectId: project.id,
            projectName: 'Orchestration Approval Project',
            source: 'EXECUTION',
            assistantId: null,
            summary: 'worker:coder:IMPLEMENT',
            payloadJson: null,
            requestedByUserId: null,
            externalUserRef: null,
            requestedAt: new Date().toISOString(),
            expiresAt: new Date(Date.now() + 3600_000).toISOString(),
          },
        ]),
      })
    })

    await adminPage.route(
      `**/api/v1/approvals/${taskId}/decide`,
      async (route, request) => {
        posted = request.postDataJSON() as {
          decision: string
          comment: string | null
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            source: 'EXECUTION',
            approvalId: taskId,
            decision: 'APPROVED',
          }),
        })
      },
    )

    await adminPage.goto('/my-work')
    await adminPage.getByRole('tab', { name: 'Approvals' }).click()

    // §17.4: the governed pending action's worker identity is the
    // summary; the row renders an Execution source badge.
    await expect(adminPage.getByText('worker:coder:IMPLEMENT')).toBeVisible()
    await expect(
      adminPage.getByRole('button', { name: 'Approve', exact: true }),
    ).toBeVisible()
    await adminPage
      .getByRole('button', { name: 'Approve', exact: true })
      .click()

    await expect.poll(() => posted, { timeout: 5_000 }).not.toBeNull()
    expect(posted?.decision).toBe('APPROVED')
  })
})