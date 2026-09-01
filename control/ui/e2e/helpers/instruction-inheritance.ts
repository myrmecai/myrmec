// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Instruction inheritance helpers for E2E tests that need to create
 * org/project-scoped instruction assets with specific availability
 * (REQUIRED/OPTIONAL) and manage project instruction bindings.
 *
 * All helpers use the admin API client, which must be logged in before use.
 */

import type { ApiClient } from './api'

export interface CreatedAsset {
  assetId: string
  versionId: string
}

/**
 * Create an org-scoped instruction asset, draft an INLINE version with the
 * given availability and CONVERSATION applicability, then publish it.
 *
 * NOTE: Under STANDARD governance, inline instructions are blocked at org
 * scope. The test must set governance to FLEXIBLE before calling this helper,
 * or use a non-inline source type.
 */
export async function createOrgInstructionAssetWithAvailability(
  api: ApiClient,
  name: string,
  availability: 'REQUIRED' | 'OPTIONAL',
): Promise<CreatedAsset> {
  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'ORGANIZATION',
    name,
    category: 'PERSONA',
  })

  const draft = await api.request<{ id: string }>('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: `Instruction content for ${name}` },
    applicability: { CONVERSATION: 'true' },
    availability,
    priority: 0,
  })

  await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`)

  return { assetId: asset.id, versionId: draft.id }
}

/**
 * Create a project-scoped instruction asset, draft an INLINE version with
 * REQUIRED availability, then publish it.
 */
export async function createProjectInstructionAsset(
  api: ApiClient,
  projectId: string,
  name: string,
): Promise<CreatedAsset> {
  const asset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
    scope: 'PROJECT',
    projectId,
    name,
    category: 'PERSONA',
  })

  const draft = await api.request<{ id: string }>('POST', `/admin/instruction-assets/${asset.id}/drafts`, {
    sourceType: 'INLINE',
    sourceDetails: { content: `Instruction content for ${name}` },
    applicability: { CONVERSATION: 'true' },
    availability: 'REQUIRED',
    priority: 0,
  })

  await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`)

  return { assetId: asset.id, versionId: draft.id }
}

/**
 * Set (upsert) an instruction binding for a project — enables/disables
 * an org OPTIONAL instruction asset for a specific project.
 */
export async function setInstructionBinding(
  api: ApiClient,
  projectId: string,
  assetId: string,
  enabled: boolean,
): Promise<void> {
  await api.request('PUT', `/admin/projects/${projectId}/instruction-bindings/${assetId}`, { enabled })
}

/**
 * Get all instruction bindings for a project.
 */
export async function getInstructionBindings(
  api: ApiClient,
  projectId: string,
): Promise<Array<{ id: string; projectId: string; instructionAssetId: string; enabled: boolean }>> {
  return api.request('GET', `/admin/projects/${projectId}/instruction-bindings`)
}