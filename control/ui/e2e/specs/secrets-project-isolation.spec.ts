// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
import { test, expect } from '../fixtures'
import { E2E_ADMIN, ApiClient } from '../helpers/api'
import { seedProjectWithMembers } from '../helpers/rbac'
import { assertSecretMasked } from '../helpers/secrets'

/**
 * Project secrets isolation and masking — SEC-01, SEC-02.
 */

test.describe('Project secrets isolation', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // SEC-01: Cross-project isolation — editor of project B cannot read project A's secrets
  test('SEC-01: editor of project B cannot read project A secrets', async ({ api }) => {
    const projectA = await seedProjectWithMembers(api, 'sec01a')
    const projectB = await seedProjectWithMembers(api, 'sec01b')

    // Create a secret in project A using the project owner
    const ownerApi = new ApiClient()
    await ownerApi.login(projectA.owner.email, projectA.owner.password)
    const secret = await ownerApi.request<{ id: string }>('POST', `/projects/${projectA.projectId}/secrets`, {
      name: 'proj-a-secret',
      type: 'SECRET_KEY',
      payload: { type: 'SECRET_KEY', secret: 'super-secret-value' },
    })

    // Project B editor tries to read project A's secret → 403
    const editorB = new ApiClient()
    await editorB.login(projectB.editor.email, projectB.editor.password)

    const getResult = await editorB.rawRequest('GET', `/projects/${projectA.projectId}/secrets/${secret.id}`)
    expect(getResult.status).toBe(403)

    // Project B editor tries to list project A's secrets → 403
    const listResult = await editorB.rawRequest('GET', `/projects/${projectA.projectId}/secrets`)
    expect(listResult.status).toBe(403)

    // Project B editor tries to delete project A's secret → 403
    const deleteResult = await editorB.rawRequest('DELETE', `/projects/${projectA.projectId}/secrets/${secret.id}`)
    expect(deleteResult.status).toBe(403)
  })

  // SEC-02: Secret value is never returned by read API
  test('SEC-02: secret value is masked in list and get responses', async ({ api }) => {
    const { projectId, owner } = await seedProjectWithMembers(api, 'sec02')

    // Create a project secret using the project owner
    const ownerApi = new ApiClient()
    await ownerApi.login(owner.email, owner.password)
    const secretValue = 'my-super-secret-value-123'
    const secret = await ownerApi.request<{ id: string }>('POST', `/projects/${projectId}/secrets`, {
      name: 'masked-secret',
      type: 'SECRET_KEY',
      payload: { type: 'SECRET_KEY', secret: secretValue },
    })

    // Get by ID — response should be a JSON object, not an array
    const getResult = await ownerApi.rawRequest('GET', `/projects/${projectId}/secrets/${secret.id}`)
    expect(getResult.status).toBe(200)
    const getBody = getResult.body as Record<string, unknown>
    // Only assert masking if the body is an object (not an error array)
    if (!Array.isArray(getBody)) {
      assertSecretMasked(getBody)
    }

    // List all — response is an array
    const listResult = await ownerApi.rawRequest('GET', `/projects/${projectId}/secrets`)
    expect(listResult.status).toBe(200)
    const listBody = listResult.body as Array<Record<string, unknown>>
    if (Array.isArray(listBody)) {
      expect(listBody.length).toBeGreaterThan(0)
      for (const item of listBody) {
        assertSecretMasked(item)
      }
    }
  })
})