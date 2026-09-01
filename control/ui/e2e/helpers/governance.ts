// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Governance helpers for E2E tests that need to set/reset the org-level
 * governance profile and assert governance violations.
 *
 * All helpers use the admin API client, which must be logged in before use.
 */

import { ApiClient } from './api'
import { expect } from '../fixtures'

export const DEFAULT_GOVERNANCE_PROFILE = 'STANDARD'

/**
 * Set the org-level default governance profile.
 */
export async function setGovernanceProfile(
  api: ApiClient,
  code: 'STRICT' | 'STANDARD' | 'FLEXIBLE',
): Promise<void> {
  await api.request('POST', `/admin/governance-profiles/${code}/set-default`)
}

/**
 * Reset governance profile to STANDARD (the default).
 */
export async function resetGovernanceProfile(api: ApiClient): Promise<void> {
  await setGovernanceProfile(api, DEFAULT_GOVERNANCE_PROFILE)
}

/**
 * Create an org-scoped instruction asset and attempt to create an INLINE draft.
 * Returns the raw response so the caller can assert on status code.
 */
export async function createOrgInstructionAssetInline(
  api: ApiClient,
  name: string,
) {
  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'ORG',
    name,
    category: 'PERSONA',
  })
  return api.rawRequest('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: 'test' },
    applicability: {},
    availability: 'REQUIRED',
    priority: 0,
  })
}

/**
 * Create a project-scoped instruction asset and attempt to create an INLINE draft.
 * Returns the raw response so the caller can assert on status code.
 */
export async function createProjectInstructionAssetInline(
  api: ApiClient,
  projectId: string,
  name: string,
) {
  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'PROJECT',
    projectId,
    name,
    category: 'PERSONA',
  })
  return api.rawRequest('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: 'test' },
    applicability: {},
    availability: 'REQUIRED',
    priority: 0,
  })
}

/**
 * Attempt to create an EXTERNAL knowledge provider.
 * Returns the raw response so the caller can assert on status code.
 */
export async function createExternalKnowledgeProvider(
  api: ApiClient,
  name: string,
) {
  return api.rawRequest('POST', '/admin/knowledge-providers', {
    scope: 'ORG',
    name,
    type: 'EXTERNAL',
  })
}

/**
 * Attempt to create a data feed with a specific feed type.
 * The governance gate runs before provider/FK validation, so a
 * placeholder providerVersionId is sufficient for governance-only tests.
 */
export async function createDataFeed(
  api: ApiClient,
  projectId: string | null,
  name: string,
  feedType: string,
) {
  return api.rawRequest('POST', '/admin/data-feeds', {
    scope: projectId ? 'PROJECT' : 'ORG',
    projectId,
    name,
    datasetName: name,
    connectionDetails: { type: feedType },
    providerVersionId: '00000000-0000-0000-0000-000000000000',
  })
}

/**
 * Assert that a raw response is a 403 GOVERNANCE_VIOLATION for the given feature.
 */
export function expectGovernanceViolation(
  res: { status: number; body: unknown },
  feature: string,
): void {
  expect(res.status).toBe(403)
  const body = res.body as { errorCode?: string; details?: Record<string, unknown> }
  expect(body.errorCode).toBe('GOVERNANCE_VIOLATION')
  expect(body.details?.feature).toBe(feature)
}