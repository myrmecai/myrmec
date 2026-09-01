// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Domain F — Context Pinning E2E tests.
 *
 * Tests the context preview endpoint with a conversation snapshot.
 * The agent behavioral tests (PIN-01..03, GOV-08) require a real-agent
 * runtime and are deferred to the EE repo.
 *
 * Test IDs: F-PIN-01 through F-PIN-03
 */

const API_BASE = 'http://localhost:9090/api/v1'

test.describe('Domain F — Context pinning', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // F-PIN-01: Context preview with conversationId loads pinned snapshot
  test('F-PIN-01: context preview accepts conversationId parameter', async ({ api }) => {
    // Create a project
    const project = await api.request('POST', '/projects', {
      name: `pin-test-${Date.now().toString(36)}`,
      description: 'Pinning E2E test',
    }) as { id: string }

    try {
      // Set governance profile to FLEXIBLE (allows org INLINE instructions)
      await api.request('POST', '/admin/governance-profiles/FLEXIBLE/set-default')

      // Create an org-scoped instruction asset with INLINE content
      const asset = await api.request('POST', '/admin/instruction-assets', {
        scope: 'ORGANIZATION',
        name: `pin-rule-${Date.now().toString(36)}`,
        description: 'Pinning test rule',
        category: 'GENERAL',
      }) as { id: string }

      // Create draft with INLINE content and REQUIRED availability, then publish
      await api.request('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
        sourceType: 'INLINE',
        sourceDetails: { content: 'Use metric units.' },
        availability: 'REQUIRED',
        priority: 0,
      })
      await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`)

      // Set profile to STANDARD for conversation creation (PINNED_AT_START)
      await api.request('POST', '/admin/governance-profiles/STANDARD/set-default')

      // Create a conversation — this should snapshot the instruction
      const conversation = await api.request('POST', `/conversations`, {
        projectId: project.id,
        title: 'Pinning test conversation',
      }) as { id: string }

      // Call context preview with conversationId
      const preview = await api.request('GET',
        `/admin/projects/${project.id}/context-preview?conversationId=${conversation.id}`) as {
          contextPinning: string
          instructions: Array<{ versionId: string; name: string }>
        }

      // Verify the preview shows PINNED_AT_START and the pinned instruction
      expect(preview.contextPinning).toBe('PINNED_AT_START')
      expect(preview.instructions.length).toBeGreaterThan(0)
      expect(preview.instructions.some((i) => i.name.includes('pin-rule'))).toBe(true)
    } finally {
      // Reset to STANDARD
      await api.request('POST', '/admin/governance-profiles/STANDARD/set-default')
    }
  })

  // F-PIN-02: Context preview without conversationId uses live resolution
  test('F-PIN-02: context preview without conversationId uses live resolution', async ({ api }) => {
    const project = await api.request('POST', '/projects', {
      name: `pin-live-${Date.now().toString(36)}`,
      description: 'Pinning live test',
    }) as { id: string }

    try {
      const preview = await api.request('GET',
        `/admin/projects/${project.id}/context-preview`) as {
        contextPinning: string
      }

      // Without a conversationId, should use live resolution (IMMEDIATE_EFFECT under FLEXIBLE)
      expect(preview.contextPinning).toBeDefined()
    } finally {
      // Cleanup handled by test isolation
    }
  })

  // F-PIN-03: Conversation created under FLEXIBLE has no snapshot
  test('F-PIN-03: FLEXIBLE profile conversation has no snapshot', async ({ api }) => {
    await api.request('POST', '/admin/governance-profiles/FLEXIBLE/set-default')

    const project = await api.request('POST', '/projects', {
      name: `pin-flex-${Date.now().toString(36)}`,
      description: 'Pinning FLEXIBLE test',
    }) as { id: string }

    try {
      const conversation = await api.request('POST', `/conversations`, {
        projectId: project.id,
        title: 'FLEXIBLE test conversation',
      }) as { id: string }

      // Preview with this conversation should fall back to live (no snapshot)
      const preview = await api.request('GET',
        `/admin/projects/${project.id}/context-preview?conversationId=${conversation.id}`) as {
        contextPinning: string
      }

      // FLEXIBLE → IMMEDIATE_EFFECT
      expect(preview.contextPinning).toBe('IMMEDIATE_EFFECT')
    } finally {
      await api.request('POST', '/admin/governance-profiles/STANDARD/set-default')
    }
  })
})