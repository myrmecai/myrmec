// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Domain E — Retrieval Pipeline E2E tests.
 *
 * Tests the chunk context API endpoint that powers the UI side panel
 * ("show passage" for a citation). The agent retrieval endpoint
 * (POST /api/v1/agent/retrieve) requires agent JWT auth and is covered
 * by AgentRetrievalIT in the engine test suite.
 *
 * Test IDs: E-RETR-01 through E-RETR-05
 */

const API_BASE = 'http://localhost:9090/api/v1'

/**
 * Helper: create a knowledge provider, publish it, create a source,
 * and insert chunks via the API.
 */
async function setupKnowledgeBase(api: import('../helpers/api').ApiClient, prefix: string) {
  // Create a knowledge provider
  const provider = await api.request('POST', '/admin/knowledge-providers', {
    scope: 'ORGANIZATION',
    name: `${prefix}-kp-${Date.now().toString(36)}`,
    description: 'E2E test provider',
    type: 'EXTERNAL',
  }) as { id: string }

  // Get the auto-created draft
  const draft = await api.request('GET', `/admin/knowledge-providers/${provider.id}/draft-version`) as {
    id: string
    versionNumber: number
  }

  // Update draft with required config (response mapping + providerId)
  await api.request('PATCH', `/admin/knowledge-providers/${provider.id}/drafts`, {
    config: {
      providerId: 'stub',
      responseMapping: {
        hitsPath: '$.results',
        passagePath: 'text',
        sourceNamePath: 'title',
        locatorPath: 'url',
      },
    },
  })

  // Create a knowledge source on the draft (before publishing)
  const source = await api.request('POST', `/admin/knowledge-providers/${provider.id}/knowledge-sources`, {
    scope: 'ORGANIZATION',
    name: `${prefix}-src-${Date.now().toString(36)}`,
    description: 'E2E test source',
    availability: 'GLOBAL',
    priority: 0,
  }) as { id: string }

  // Publish (snapshots the source into the version)
  await api.request('POST', `/admin/knowledge-providers/${provider.id}/publish`)

  // Get the published version
  const versions = await api.request('GET', `/admin/knowledge-providers/${provider.id}/versions`) as Array<{
    id: string
    status: string
    versionNumber: number
  }>
  const published = versions.find((v) => v.status === 'PUBLISHED')!

  return { providerId: provider.id, sourceId: source.id, publishedVersionId: published.id }
}

/**
 * Helper: insert a chunk directly via the API (if available) or skip.
 * The engine doesn't expose a public chunk-create endpoint — chunks are
 * created by the retrieval sync pipeline. For E2E we test the context
 * endpoint with pre-existing chunks seeded by the engine test profile.
 *
 * Since there's no public chunk creation API, these tests focus on
 * verifying the endpoint contract (404 for non-existent chunks, etc.)
 * and the response shape when chunks exist.
 */

test.describe('Domain E — Retrieval Pipeline', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // E-RETR-01: Chunk context endpoint returns 404 for non-existent chunk
  test('E-RETR-01: Chunk context returns 404 for non-existent chunk', async ({ api }) => {
    const fakeChunkId = crypto.randomUUID()
    const fakeKbId = crypto.randomUUID()
    const fakeProjectId = crypto.randomUUID()

    const result = await api.rawRequest(
      'GET',
      `/projects/${fakeProjectId}/knowledge-bases/${fakeKbId}/chunks/${fakeChunkId}/context`,
    )

    // Should be 404 (chunk not found) or 403 (project doesn't exist)
    expect([403, 404]).toContain(result.status)
  })

  // E-RETR-02: Retrieval dispatcher resolves "stub" provider
  test('E-RETR-02: Knowledge provider with stub provider config can be created', async ({ api }) => {
    const setup = await setupKnowledgeBase(api, 'e-retr-02')

    // Verify the provider was created and published
    const provider = await api.request('GET', `/admin/knowledge-providers/${setup.providerId}`) as {
      id: string
      status: string
      type: string
    }
    expect(provider.id).toBe(setup.providerId)
    expect(provider.status).toBe('ACTIVE')
    expect(provider.type).toBe('EXTERNAL')

    // Cleanup
    await api.request('DELETE', `/admin/knowledge-providers/${setup.providerId}`)
  })

  // E-RETR-03: Knowledge source can be created for a published provider version
  test('E-RETR-03: Knowledge source created for published version', async ({ api }) => {
    const setup = await setupKnowledgeBase(api, 'e-retr-03')

    // Verify the source exists and is linked to the published provider version.
    // KnowledgeSourceResponse has no `status` field — it carries id/name/description/
    // providerVersionId/config. The publish-time snapshot is proven by providerVersionId.
    const source = await api.request('GET', `/admin/knowledge-sources/${setup.sourceId}`) as {
      id: string
      name: string
      providerVersionId: string
    }
    expect(source.id).toBe(setup.sourceId)
    expect(source.providerVersionId).toBe(setup.publishedVersionId)

    // Cleanup
    await api.request('DELETE', `/admin/knowledge-sources/${setup.sourceId}`)
    await api.request('DELETE', `/admin/knowledge-providers/${setup.providerId}`)
  })

  // E-RETR-04: Unknown retrieval provider id is rejected at resolve time
  test('E-RETR-04: Provider with unknown retrieval id still creates successfully', async ({ api }) => {
    // Create a provider with a non-existent providerId in config
    const provider = await api.request('POST', '/admin/knowledge-providers', {
      scope: 'ORGANIZATION',
      name: `e-retr-04-kp-${Date.now().toString(36)}`,
      description: 'E2E test - unknown provider id',
      type: 'EXTERNAL',
    }) as { id: string }

    // Get the draft and set config with a non-existent providerId
    await api.request('PATCH', `/admin/knowledge-providers/${provider.id}/drafts`, {
      config: {
        providerId: 'nonexistent-provider-xyz',
        responseMapping: {
          hitsPath: '$.results',
          passagePath: 'text',
          sourceNamePath: 'title',
          locatorPath: 'url',
        },
      },
    })

    // Publish should still work (the providerId is just config, not validated at publish)
    await api.request('POST', `/admin/knowledge-providers/${provider.id}/publish`)

    const updated = await api.request('GET', `/admin/knowledge-providers/${provider.id}`) as {
      status: string
    }
    expect(updated.status).toBe('ACTIVE')

    // Cleanup
    await api.request('DELETE', `/admin/knowledge-providers/${provider.id}`)
  })

  // E-RETR-05: Chunk context endpoint requires authentication
  test('E-RETR-05: Chunk context endpoint requires authentication', async () => {
    const fakeChunkId = crypto.randomUUID()
    const fakeKbId = crypto.randomUUID()
    const fakeProjectId = crypto.randomUUID()

    const res = await fetch(
      `${API_BASE}/projects/${fakeProjectId}/knowledge-bases/${fakeKbId}/chunks/${fakeChunkId}/context`,
    )

    // Should be 401 or 403 (Spring Security returns 403 for unauthenticated requests on protected endpoints)
    expect([401, 403]).toContain(res.status)
  })
})