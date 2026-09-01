// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Context assembly helpers for CTX-01..CTX-13 E2E tests.
 *
 * All helpers use the admin API client, which must be logged in before use.
 * Helpers create org and project instruction assets with controlled status,
 * availability, applicability, priority, and activation rules.
 */

import type { ApiClient } from './api'
import { setGovernanceProfile } from './governance'

export interface CreatedAsset {
  assetId: string
  versionId: string
}

export interface ContextPreviewResponse {
  governanceProfileCode: string
  contextPinning: string
  totalTokens: number
  budgetTokens: number
  truncated: boolean
  instructions: Array<{
    assetId: string
    versionId: string
    name: string
    scope: string
    category: string
    sourceType: string
    contentPreview: string | null
    gitCommit: string | null
    inlineVersion: number | null
    priority: number
    tokenCount: number
  }>
  knowledgeSources: Array<{
    sourceId: string
    sourceName: string
    providerType: string
    datasetName: string | null
    availability: string
  }>
}

/**
 * Set governance to FLEXIBLE for the test (allows org-scoped INLINE).
 */
export async function useFlexibleGovernance(api: ApiClient): Promise<void> {
  await setGovernanceProfile(api, 'FLEXIBLE')
}

/**
 * Create an org-scoped ACTIVE+PUBLISHED instruction asset with INLINE content
 * and the given availability, applicability, priority, and optional activation rules.
 */
export async function createOrgInstructionAsset(
  api: ApiClient,
  name: string,
  opts: {
    availability?: 'REQUIRED' | 'OPTIONAL'
    applicability?: Record<string, unknown>
    priority?: number
    activationRules?: Record<string, unknown>
  } = {},
): Promise<CreatedAsset> {
  const { availability = 'REQUIRED', applicability = { CONVERSATION: 'true' }, priority = 0, activationRules } = opts

  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'ORGANIZATION',
    name,
    category: 'STANDARD',
  })

  const draft = await api.request<{ id: string }>('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: `Instruction content for ${name}` },
    applicability,
    availability,
    priority,
    activationRules: activationRules ?? null,
  })

  await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`)

  return { assetId: asset.id, versionId: draft.id }
}

/**
 * Create a project-scoped ACTIVE+PUBLISHED instruction asset.
 */
export async function createProjectInstructionAsset(
  api: ApiClient,
  projectId: string,
  name: string,
  opts: {
    priority?: number
    applicability?: Record<string, unknown>
  } = {},
): Promise<CreatedAsset> {
  const { priority = 0, applicability = { CONVERSATION: 'true' } } = opts

  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'PROJECT',
    projectId,
    name,
    category: 'STANDARD',
  })

  const draft = await api.request<{ id: string }>('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: `Project instruction content for ${name}` },
    applicability,
    availability: 'REQUIRED',
    priority,
    activationRules: null,
  })

  await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`)

  return { assetId: asset.id, versionId: draft.id }
}

/**
 * Create an org-scoped INCOMPLETE instruction asset (no published version).
 */
export async function createIncompleteOrgAsset(api: ApiClient, name: string): Promise<string> {
  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'ORGANIZATION',
    name,
    category: 'STANDARD',
  })
  return asset.id
}

/**
 * Create an org-scoped asset, publish it, then disable it.
 */
export async function createDisabledOrgAsset(api: ApiClient, name: string): Promise<string> {
  const created = await createOrgInstructionAsset(api, name)
  await api.request('POST', `/admin/instruction-assets/${created.assetId}/disable`)
  return created.assetId
}

/**
 * Create an org-scoped asset, publish it, disable it, then archive it.
 */
export async function createArchivedOrgAsset(api: ApiClient, name: string): Promise<string> {
  const assetId = await createDisabledOrgAsset(api, name)
  await api.request('POST', `/admin/instruction-assets/${assetId}/archive`)
  return assetId
}

/**
 * Enable an org OPTIONAL instruction asset for a project.
 */
export async function enableProjectBinding(
  api: ApiClient,
  projectId: string,
  assetId: string,
): Promise<void> {
  await api.request('PUT', `/admin/projects/${projectId}/instruction-bindings/${assetId}`, { enabled: true })
}

/**
 * Call the context-preview endpoint and return the resolved context.
 */
export async function getContextPreview(
  api: ApiClient,
  projectId: string,
  opts: { serviceType?: string; fileType?: string } = {},
): Promise<ContextPreviewResponse> {
  const { serviceType = 'CONVERSATION', fileType } = opts
  let path = `/admin/projects/${projectId}/context-preview?serviceType=${serviceType}`
  if (fileType) {
    path += `&fileType=${fileType}`
  }
  return api.request('GET', path)
}

/**
 * Clean up an instruction asset (disable, archive, delete).
 */
export async function cleanupAsset(api: ApiClient, assetId: string): Promise<void> {
  try { await api.request('POST', `/admin/instruction-assets/${assetId}/disable`) } catch { /* ignore */ }
  try { await api.request('POST', `/admin/instruction-assets/${assetId}/archive`) } catch { /* ignore */ }
  try { await api.request('DELETE', `/admin/instruction-assets/${assetId}`) } catch { /* gone */ }
}