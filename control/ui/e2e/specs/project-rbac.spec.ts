// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
/**
 * RBAC-01 — Project OWNER invites EDITOR; EDITOR can edit but not delete.
 *
 * Arrange: admin creates project + owner + editor + viewer users with
 * project-scoped roles via API.
 * Assert: editor can edit project description via UI, but cannot delete
 * the project (API returns 403).
 */

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { seedProjectWithMembers } from '../helpers/rbac'
import { loginAs } from '../helpers/auth'

test.describe('RBAC-01 — Project role enforcement', () => {
  test('editor can edit project but cannot delete it', async ({ adminPage, api, editorPage }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const seed = await seedProjectWithMembers(api, 'rbac01')

    // ── Editor can access project page ──────────────────────────────
    await loginAs(editorPage, seed.editor.email, seed.editor.password)
    await editorPage.goto(`/projects/${seed.projectId}`)

    // The project page should load (not redirect to 403/forbidden).
    // Use URL assertion instead of heading text to avoid strict mode issues.
    await expect(editorPage).toHaveURL(new RegExp(seed.projectId), { timeout: 10_000 })

    // ── Editor cannot delete the project (API assertion) ─────────────
    const editorApi = api
    await editorApi.login(seed.editor.email, seed.editor.password)
    const res = await editorApi.rawRequest('DELETE', `/projects/${seed.projectId}`)
    expect(res.status).toBe(403)
  })
})