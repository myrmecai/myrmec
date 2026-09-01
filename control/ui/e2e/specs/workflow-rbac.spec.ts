// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
/**
 * RBAC-02 — VIEWER cannot trigger workflow execution.
 *
 * Arrange: admin creates project + owner + viewer, creates and publishes
 * a workflow in the project.
 * Assert: viewer's direct API call to start a workflow execution returns 403.
 * UI: viewer sees the published workflow (button may be visible — the
 * security boundary is enforced at the API level).
 */

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { seedProjectWithMembers } from '../helpers/rbac'
import { loginAs } from '../helpers/auth'

test.describe('RBAC-02 — VIEWER cannot start workflow execution', () => {
  test('viewer API call to start execution returns 403', async ({ api, viewerPage }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const seed = await seedProjectWithMembers(api, 'rbac02', {
      allowedServiceTypes: ['WORKFLOW'],
    })

    // Create a minimal agent profile (required for workflow steps)
    const profile = await api.request<{ id: string }>('POST', '/admin/agent-profiles', {
      name: `rbac02-profile-${Date.now().toString(36)}`,
      capabilities: [],
      toolCodes: [],
      systemPrompt: 'Stub',
      defaultModel: 'github-gpt-4o',
    })

    // Create and publish a workflow
    const workflow = await api.request<{ id: string }>(
      'POST',
      `/projects/${seed.projectId}/workflows`,
      {
        projectId: seed.projectId,
        name: `rbac02-workflow-${Date.now().toString(36)}`,
        steps: [{
          id: 's1',
          name: 'Step 1',
          agentProfileId: profile.id,
          prompt: 'Return done',
          dependsOn: [],
          transitions: {},
          timeoutSeconds: 300,
          maxRetries: 0,
          pauseMode: 'NONE',
        }],
      },
    )
    await api.request('POST', `/projects/${seed.projectId}/workflows/${workflow.id}/publish`)

    // ── Viewer API assertion: POST /requests returns 403 ─────────────
    await api.login(seed.viewer.email, seed.viewer.password)
    const res = await api.rawRequest(
      'POST',
      `/projects/${seed.projectId}/workflows/${workflow.id}/requests`,
      { workflowId: workflow.id, input: {} },
    )
    // Debug: log the actual status and body
    console.log(`[RBAC-02] Viewer request: status=${res.status}, body=${JSON.stringify(res.body).substring(0, 300)}`)
    expect(res.status).toBe(403)

    // ── Owner API assertion: POST /requests succeeds ─────────────────
    await api.login(seed.owner.email, seed.owner.password)
    const ownerRes = await api.rawRequest(
      'POST',
      `/projects/${seed.projectId}/workflows/${workflow.id}/requests`,
      { workflowId: workflow.id, input: {} },
    )
    expect(ownerRes.status).toBe(201)
  })
})