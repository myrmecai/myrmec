// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Agent registration and health — AGT-02.
 *
 * Note: AGT-03 (register agent + see it in the list) was removed as redundant —
 * agents-admin.spec.ts already covers create + list visibility at the UI layer,
 * which reads the same /admin/agent-hosts endpoint. This spec keeps the unique
 * AGT-02 health-snapshot coverage.
 */

test.describe('Agent registration and health', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // AGT-02: Agent health endpoint returns health status based on heartbeat
  test('AGT-02: agent health endpoint returns health snapshot', async ({ api }) => {
    // Create a profile and agent
    const profile = await api.request<{ id: string }>('POST', '/admin/agent-profiles', {
      name: `agt02-profile-${Date.now().toString(36)}`,
      description: 'AGT-02 test profile',
      capabilities: [],
      supportedTools: [],
      toolCodes: [],
      systemPrompt: 'You are a test agent.',
      defaultModel: 'github-gpt-4o',
    })

    const agentResp = await api.request<{ agent: { id: string }; registrationKey: string }>('POST', '/admin/agent-hosts', {
      name: `agt02-agent-${Date.now().toString(36)}`,
      description: 'AGT-02 test agent',
      profileId: profile.id,
      maxAgents: 1,
    })

    // Query the health endpoint — without any running instances, it should return
    // a snapshot with zero instances (no agent process running)
    const health = await api.request<{
      agentId: string
      totalInstances: number
      onlineInstances: number
      instances: Array<{ status: string }>
    }>('GET', `/admin/agent-hosts/${agentResp.agent.id}/health`)

    expect(health.agentId).toBe(agentResp.agent.id)
    expect(health.totalInstances).toBeDefined()
    // With no running instances, total should be 0
    expect(health.totalInstances).toBe(0)
  })
})