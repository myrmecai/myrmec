import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Phase 7d — HITL approval card rendering + decision flow.
 *
 * <p>Approach: arrange-via-API for project + conversation, then use
 * Playwright route interception to inject a synthetic
 * {@code APPROVAL_REQUEST} row into the conversation messages REST
 * response. The real engine has no admin-only seed endpoint for
 * approvals (they only arrive over the agent WebSocket), and the
 * intent of this spec is to prove the UI renderers + button wiring,
 * not the agent loop — that's covered by
 * {@code HitlApprovalLoopE2ETest} on the engine side.</p>
 *
 * <p>The POST {@code /approvals/{id}} call is also intercepted so the
 * spec runs without depending on a pinned agent on the conversation.</p>
 */
test.describe('Phase 7d approval card', () => {
  test('renders a SQL approval and submits an APPROVED decision', async ({
    api,
    adminPage,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `hitl-card-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>(
      'POST',
      '/conversations',
      { projectId: project.id, title: 'hitl card spec' },
    )

    const approvalId = '11111111-1111-1111-1111-111111111111'
    const clientReqId = '22222222-2222-2222-2222-222222222222'

    // 1) Inject one APPROVAL_REQUEST row into the messages list.
    let decisionStatus: 'PENDING' | 'APPROVED' | 'REJECTED' = 'PENDING'
    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/messages`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: approvalId,
              conversationId: conversation.id,
              sequenceNo: 1,
              role: 'APPROVAL_REQUEST',
              content: 'Run destructive SQL on production?',
              authorUserId: null,
              authorAgentId: null,
              modelCode: null,
              tokenCount: null,
              toolCallId: null,
              parentMessageId: null,
              payloadJson: JSON.stringify({
                clientRequestId: clientReqId,
                sql: 'DROP TABLE users;',
              }),
              approvalStatus: decisionStatus,
              approverId: null,
              expiresAt: null,
              createdAt: new Date().toISOString(),
            },
          ]),
        })
      },
    )

    // 2) Capture the decision POST so we can assert + flip the status
    //    to APPROVED for the follow-up invalidation refetch.
    let posted: { decision: string; comment: string | null } | null = null
    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/approvals/${approvalId}`,
      async (route, request) => {
        posted = request.postDataJSON() as {
          decision: string
          comment: string | null
        }
        decisionStatus = posted.decision === 'APPROVED' ? 'APPROVED' : 'REJECTED'
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    // Card + payload renderer.
    const card = adminPage.getByTestId('message-approval-request')
    await expect(card).toBeVisible()
    await expect(card).toHaveAttribute('data-status', 'PENDING')
    await expect(adminPage.getByTestId('approval-summary')).toContainText(
      'Run destructive SQL',
    )
    await expect(adminPage.getByTestId('approval-payload-sql')).toContainText(
      'DROP TABLE users;',
    )

    // Approve with a comment.
    await adminPage.getByTestId('approval-comment').fill('green-lit by ops')
    await adminPage.getByTestId('approval-approve').click()

    await expect.poll(() => posted, { timeout: 5_000 }).not.toBeNull()
    expect(posted!.decision).toBe('APPROVED')
    expect(posted!.comment).toBe('green-lit by ops')

    // After invalidation the mock returns APPROVED — buttons hide.
    await expect(card).toHaveAttribute('data-status', 'APPROVED')
    await expect(adminPage.getByTestId('approval-approve')).toHaveCount(0)
  })

  test('renders a diff approval payload with old/new panes', async ({
    api,
    adminPage,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `hitl-diff-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>(
      'POST',
      '/conversations',
      { projectId: project.id, title: 'hitl diff spec' },
    )

    const approvalId = '33333333-3333-3333-3333-333333333333'

    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/messages`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: approvalId,
              conversationId: conversation.id,
              sequenceNo: 1,
              role: 'APPROVAL_REQUEST',
              content: 'Apply patch to config.yaml',
              authorUserId: null,
              authorAgentId: null,
              modelCode: null,
              tokenCount: null,
              toolCallId: null,
              parentMessageId: null,
              payloadJson: JSON.stringify({
                clientRequestId: '44444444-4444-4444-4444-444444444444',
                title: 'config.yaml',
                oldText: 'replicas: 1',
                newText: 'replicas: 3',
              }),
              approvalStatus: 'PENDING',
              approverId: null,
              expiresAt: null,
              createdAt: new Date().toISOString(),
            },
          ]),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    const card = adminPage.getByTestId('message-approval-request')
    await expect(card).toBeVisible()
    await expect(
      adminPage.getByTestId('approval-payload-diff-old'),
    ).toContainText('replicas: 1')
    await expect(
      adminPage.getByTestId('approval-payload-diff-new'),
    ).toContainText('replicas: 3')
  })
})
