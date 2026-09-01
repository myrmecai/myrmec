// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * HITL approval edge cases — HITL-03, HITL-05.
 *
 * Uses route interception to inject synthetic approval messages,
 * following the same pattern as approval-card.spec.ts.
 */

test.describe('HITL edge cases', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // HITL-03: Shell-command approval card renders command + destructive risk class
  test('HITL-03: shell-command approval card renders command text', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `hitl03-${Date.now().toString(36)}`,
    })
    const conversation = await api.request<{ id: string }>('POST', '/conversations', {
      projectId: project.id,
      title: 'hitl03 spec',
    })

    const approvalId = '33333333-3333-3333-3333-333333333333'
    const command = 'rm -rf /tmp/old-builds'

    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/messages*`,
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
              content: 'Run shell command?',
              authorUserId: null,
              authorAgentId: null,
              modelCode: null,
              tokenCount: null,
              toolCallId: null,
              parentMessageId: null,
              payloadJson: JSON.stringify({
                tool: 'shell',
                command,
                riskClass: 'DESTRUCTIVE',
              }),
              approvalStatus: 'PENDING',
              approverId: null,
              expiresAt: null,
              pinned: false,
              feedbackRating: null,
              feedbackReason: null,
              feedbackBy: null,
              feedbackAt: null,
              superseded: false,
              createdAt: new Date().toISOString(),
            },
          ]),
        })
      },
    )

    // Intercept the decision POST
    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/approvals/${approvalId}`,
      async (route) => {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    // Card should render
    const card = adminPage.getByTestId('message-approval-request')
    await expect(card).toBeVisible()
    await expect(card).toHaveAttribute('data-status', 'PENDING')

    // The command text should be visible
    await expect(adminPage.getByTestId('approval-summary')).toContainText('shell')
    await expect(adminPage.locator('body')).toContainText(command)
  })

  // HITL-05: Approval auto-denies after configured expiry window
  test('HITL-05: expired approval shows EXPIRED status', async ({ api, adminPage }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `hitl05-${Date.now().toString(36)}`,
    })
    const conversation = await api.request<{ id: string }>('POST', '/conversations', {
      projectId: project.id,
      title: 'hitl05 spec',
    })

    const approvalId = '44444444-4444-4444-4444-444444444444'
    // Set expiry in the past so the approval is already expired
    const expiredTime = new Date(Date.now() - 60000).toISOString()

    await adminPage.route(
      `**/api/v1/conversations/${conversation.id}/messages*`,
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
              content: 'Run destructive SQL?',
              authorUserId: null,
              authorAgentId: null,
              modelCode: null,
              tokenCount: null,
              toolCallId: null,
              parentMessageId: null,
              payloadJson: JSON.stringify({
                sql: 'DELETE FROM users WHERE 1=1;',
              }),
              approvalStatus: 'EXPIRED',
              approverId: null,
              expiresAt: expiredTime,
              pinned: false,
              feedbackRating: null,
              feedbackReason: null,
              feedbackBy: null,
              feedbackAt: null,
              superseded: false,
              createdAt: new Date(Date.now() - 120000).toISOString(),
            },
          ]),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    // Card should show EXPIRED status
    const card = adminPage.getByTestId('message-approval-request')
    await expect(card).toBeVisible()
    await expect(card).toHaveAttribute('data-status', 'EXPIRED')

    // Approve/Reject buttons should NOT be visible for expired approvals
    await expect(adminPage.getByTestId('approval-approve')).toHaveCount(0)
    await expect(adminPage.getByTestId('approval-reject')).toHaveCount(0)
  })
})