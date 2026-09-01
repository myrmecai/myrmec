// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * CTX-01..CTX-13 — Context Assembly E2E tests (Tier 2).
 *
 * Verifies the ContextBuilder resolution pipeline through the admin
 * context-preview endpoint: status filtering, availability/binding,
 * priority ordering, scope ordering, applicability filtering,
 * activation rules, manifest completeness, and token budget enforcement.
 *
 * Arrange-via-API; assert-via-API (context-preview endpoint).
 */

import { test, expect } from '../../fixtures'
import { E2E_ADMIN } from '../../helpers/api'
import {
  createOrgInstructionAsset,
  createProjectInstructionAsset,
  createIncompleteOrgAsset,
  createDisabledOrgAsset,
  createArchivedOrgAsset,
  enableProjectBinding,
  getContextPreview,
  useFlexibleGovernance,
  cleanupAsset,
} from '../../helpers/context-assembly'
import { resetGovernanceProfile } from '../../helpers/governance'

const UNIQUE = Date.now().toString(36)

test.beforeEach(async ({ api }) => {
  await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  // FLEXIBLE allows org-scoped INLINE instructions
  await useFlexibleGovernance(api)
})

test.afterEach(async ({ api }) => {
  await resetGovernanceProfile(api)
})

// ── CTX-01: Only ACTIVE+PUBLISHED assets included ──────────────────

test.describe('CTX-01 — only ACTIVE+PUBLISHED assets included', () => {
  test('active published asset appears in context preview', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-01-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx01-active-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      expect(preview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx01-active-${UNIQUE}`, scope: 'ORGANIZATION' }),
        ]),
      )
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-02: DISABLED assets excluded ───────────────────────────────

test.describe('CTX-02 — DISABLED assets excluded', () => {
  test('disabled asset does not appear in context preview', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-02-${UNIQUE}`,
    })
    const assetId = await createDisabledOrgAsset(api, `ctx02-disabled-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      expect(preview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx02-disabled-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-03: INCOMPLETE assets excluded ─────────────────────────────

test.describe('CTX-03 — INCOMPLETE assets excluded', () => {
  test('incomplete asset does not appear in context preview', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-03-${UNIQUE}`,
    })
    const assetId = await createIncompleteOrgAsset(api, `ctx03-incomplete-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      expect(preview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx03-incomplete-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-04: ARCHIVED assets excluded ───────────────────────────────

test.describe('CTX-04 — ARCHIVED assets excluded', () => {
  test('archived asset does not appear in context preview', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-04-${UNIQUE}`,
    })
    const assetId = await createArchivedOrgAsset(api, `ctx04-archived-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      expect(preview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx04-archived-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-05: REQUIRED assets always included ────────────────────────

test.describe('CTX-05 — REQUIRED assets always included', () => {
  test('required org asset appears without project binding', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-05-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx05-required-${UNIQUE}`, {
      availability: 'REQUIRED',
    })

    try {
      const preview = await getContextPreview(api, project.id)
      expect(preview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx05-required-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-06: OPTIONAL assets only included when enabled ─────────────

test.describe('CTX-06 — OPTIONAL assets only included when enabled', () => {
  test('optional org asset excluded without binding, included when enabled', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-06-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx06-optional-${UNIQUE}`, {
      availability: 'OPTIONAL',
    })

    try {
      // Without binding → excluded
      const before = await getContextPreview(api, project.id)
      expect(before.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx06-optional-${UNIQUE}` }),
        ]),
      )

      // Enable binding → included
      await enableProjectBinding(api, project.id, asset.assetId)
      const after = await getContextPreview(api, project.id)
      expect(after.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx06-optional-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-07: Priority ordering — higher priority = later in stack ───

test.describe('CTX-07 — priority ordering', () => {
  test('higher priority instruction appears later in the stack', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-07-${UNIQUE}`,
    })
    const low = await createOrgInstructionAsset(api, `ctx07-low-${UNIQUE}`, { priority: 10 })
    const high = await createOrgInstructionAsset(api, `ctx07-high-${UNIQUE}`, { priority: 500 })

    try {
      const preview = await getContextPreview(api, project.id)
      const names = preview.instructions.map((i) => i.name)
      const lowIdx = names.indexOf(`ctx07-low-${UNIQUE}`)
      const highIdx = names.indexOf(`ctx07-high-${UNIQUE}`)
      expect(lowIdx).toBeGreaterThanOrEqual(0)
      expect(highIdx).toBeGreaterThanOrEqual(0)
      // Ascending: low priority first, high priority later
      expect(lowIdx).toBeLessThan(highIdx)
    } finally {
      await cleanupAsset(api, low.assetId)
      await cleanupAsset(api, high.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-08: Scope ordering — Org before Project ────────────────────

test.describe('CTX-08 — scope ordering', () => {
  test('org asset appears before project asset in resolved stack', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-08-${UNIQUE}`,
    })
    // Org asset with priority 100 → effective 1100
    const orgAsset = await createOrgInstructionAsset(api, `ctx08-org-${UNIQUE}`, { priority: 100 })
    // Project asset with priority 100 → effective 2100
    const projAsset = await createProjectInstructionAsset(api, project.id, `ctx08-proj-${UNIQUE}`, { priority: 100 })

    try {
      const preview = await getContextPreview(api, project.id)
      const names = preview.instructions.map((i) => i.name)
      const orgIdx = names.indexOf(`ctx08-org-${UNIQUE}`)
      const projIdx = names.indexOf(`ctx08-proj-${UNIQUE}`)
      expect(orgIdx).toBeGreaterThanOrEqual(0)
      expect(projIdx).toBeGreaterThanOrEqual(0)
      // Org (1100) should come before Project (2100) in ascending order
      expect(orgIdx).toBeLessThan(projIdx)
    } finally {
      await cleanupAsset(api, orgAsset.assetId)
      await cleanupAsset(api, projAsset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-09: Applicability filtering ────────────────────────────────

test.describe('CTX-09 — applicability filtering', () => {
  test('CONVERSATION-only asset excluded from WORKFLOW context', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-09-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx09-conv-only-${UNIQUE}`, {
      applicability: { CONVERSATION: 'true' },
    })

    try {
      // WORKFLOW context → excluded
      const wfPreview = await getContextPreview(api, project.id, { serviceType: 'WORKFLOW' })
      expect(wfPreview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx09-conv-only-${UNIQUE}` }),
        ]),
      )

      // CONVERSATION context → included
      const convPreview = await getContextPreview(api, project.id, { serviceType: 'CONVERSATION' })
      expect(convPreview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx09-conv-only-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-10: Activation rules — file-type filtering ─────────────────

test.describe('CTX-10 — file-type activation rules', () => {
  test('SQL activation rule matches SQL fileType, excludes PYTHON', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-10-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx10-sql-rule-${UNIQUE}`, {
      activationRules: { fileType: 'SQL' },
    })

    try {
      // fileType=SQL → included
      const sqlPreview = await getContextPreview(api, project.id, { fileType: 'SQL' })
      expect(sqlPreview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx10-sql-rule-${UNIQUE}` }),
        ]),
      )

      // fileType=PYTHON → excluded
      const pyPreview = await getContextPreview(api, project.id, { fileType: 'PYTHON' })
      expect(pyPreview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx10-sql-rule-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-11: Manifest records included assets with versions ─────────

test.describe('CTX-11 — manifest records included assets', () => {
  test('preview response includes assetId, versionId, scope, priority, tokenCount', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-11-${UNIQUE}`,
    })
    const asset = await createOrgInstructionAsset(api, `ctx11-manifest-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      const entry = preview.instructions.find((i) => i.name === `ctx11-manifest-${UNIQUE}`)
      expect(entry).toBeDefined()
      expect(entry!.assetId).toBeTruthy()
      expect(entry!.versionId).toBeTruthy()
      expect(entry!.scope).toBe('ORGANIZATION')
      expect(typeof entry!.priority).toBe('number')
      expect(typeof entry!.tokenCount).toBe('number')
    } finally {
      await cleanupAsset(api, asset.assetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-12: Manifest records excluded assets with reason ────────────

test.describe('CTX-12 — manifest records excluded assets with reason', () => {
  test('disabled asset is not in instructions list', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-12-${UNIQUE}`,
    })
    const activeAsset = await createOrgInstructionAsset(api, `ctx12-active-${UNIQUE}`)
    const disabledAssetId = await createDisabledOrgAsset(api, `ctx12-disabled-${UNIQUE}`)

    try {
      const preview = await getContextPreview(api, project.id)
      // Active asset should be present
      expect(preview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx12-active-${UNIQUE}` }),
        ]),
      )
      // Disabled asset should be absent
      expect(preview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx12-disabled-${UNIQUE}` }),
        ]),
      )
    } finally {
      await cleanupAsset(api, activeAsset.assetId)
      await cleanupAsset(api, disabledAssetId)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})

// ── CTX-13: Token budget enforcement ───────────────────────────────

test.describe('CTX-13 — token budget enforcement', () => {
  test('low-priority asset truncated when budget exceeded', async ({ api }) => {
    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `CTX-13-${UNIQUE}`,
    })

    // High-priority small asset
    const high = await createOrgInstructionAsset(api, `ctx13-high-${UNIQUE}`, { priority: 500 })

    // Low-priority large asset — create via API with lots of content
    const lowAsset = await api.request<{ id: string }>('POST', '/admin/instruction-assets', {
      scope: 'ORGANIZATION',
      name: `ctx13-low-${UNIQUE}`,
      category: 'STANDARD',
    })
    await api.request('POST', `/admin/instruction-assets/${lowAsset.id}/drafts`, {
      sourceType: 'INLINE',
      sourceDetails: { content: 'B'.repeat(100000) },
      applicability: { CONVERSATION: 'true' },
      availability: 'REQUIRED',
      priority: 1,
      activationRules: null,
    })
    await api.request('POST', `/admin/instruction-assets/${lowAsset.id}/publish`)

    try {
      const preview = await getContextPreview(api, project.id)
      // High-priority should be included
      expect(preview.instructions).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx13-high-${UNIQUE}` }),
        ]),
      )
      // Low-priority should be truncated
      expect(preview.instructions).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ name: `ctx13-low-${UNIQUE}` }),
        ]),
      )
      // Truncated flag should be true
      expect(preview.truncated).toBe(true)
    } finally {
      await cleanupAsset(api, high.assetId)
      await cleanupAsset(api, lowAsset.id)
      await api.request('DELETE', `/projects/${project.id}`).catch(() => {})
    }
  })
})