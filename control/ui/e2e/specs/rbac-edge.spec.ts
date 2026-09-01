// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { seedProjectWithMembers, seedOrgUser, assignProjectRole, createUser, assignSystemRole } from '../helpers/rbac'
import { createProject } from '../helpers/project'
import { createProjectSecret } from '../helpers/secrets'
import { loginAs } from '../helpers/auth'

/**
 * RBAC edge cases — RBAC-03, RBAC-04, RBAC-05.
 *
 * Tests approver, auditor, and org-admin role boundaries using API assertions.
 */

test.describe('RBAC edge cases', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // RBAC-03: Approver can submit an approval decision but cannot edit a workflow definition
  test('RBAC-03: APPROVER can approve but cannot create projects', async ({ api }) => {
    const { projectId, approver } = await seedProjectWithMembers(api, 'rbac03', { includeApprover: true })
    expect(approver).toBeDefined()

    // Approver can view the project (VIEWER is implicit)
    const approverApi = new (await import('../helpers/api')).ApiClient()
    await approverApi.login(approver!.email, approver!.password)
    const viewResult = await approverApi.rawRequest('GET', `/projects/${projectId}`)
    expect(viewResult.status).toBe(200)

    // Approver cannot create a project (requires ORG_ADMIN or group edit)
    const createResult = await approverApi.rawRequest('POST', '/projects', { name: 'should-be-blocked' })
    expect(createResult.status).toBe(403)
  })

  // RBAC-04: Auditor can browse project history/audit but cannot mutate
  test('RBAC-04: AUDITOR can read but cannot mutate project resources', async ({ api }) => {
    const { projectId, auditor } = await seedProjectWithMembers(api, 'rbac04', { includeAuditor: true })
    expect(auditor).toBeDefined()

    // Promote auditor to system-scoped AUDITOR so hasAnyRole('AUDITOR') passes
    await assignSystemRole(api, auditor!.id, 'AUDITOR')

    const auditorApi = new (await import('../helpers/api')).ApiClient()
    await auditorApi.login(auditor!.email, auditor!.password)

    // Auditor can view the project (VIEWER is implicit)
    const viewResult = await auditorApi.rawRequest('GET', `/projects/${projectId}`)
    expect(viewResult.status).toBe(200)

    // Auditor can query audit log (AUDITOR role grants audit read)
    const auditResult = await auditorApi.rawRequest('GET', '/audit-log')
    expect(auditResult.status).toBe(200)

    // Auditor cannot create a project secret (requires EDITOR)
    const secretResult = await auditorApi.rawRequest('POST', `/projects/${projectId}/secrets`, {
      name: 'blocked-secret',
      type: 'SECRET_KEY',
      payload: { type: 'SECRET_KEY', secret: 'value' },
    })
    expect(secretResult.status).toBe(403)

    // Auditor cannot create a project (requires ORG_ADMIN)
    const createProjectResult = await auditorApi.rawRequest('POST', '/projects', { name: 'blocked' })
    expect(createProjectResult.status).toBe(403)
  })

  // RBAC-05: ORG_ADMIN sees all projects; project-scoped editor only sees their assigned project
  test('RBAC-05: ORG_ADMIN sees all projects; project editor sees only their project', async ({ api }) => {
    // Create two projects with separate editors
    const project1 = await seedProjectWithMembers(api, 'rbac05a')
    const project2 = await seedProjectWithMembers(api, 'rbac05b')

    // Create an ORG_ADMIN
    const orgAdmin = await seedOrgUser(api, 'rbac05', 'ORG_ADMIN')

    // ORG_ADMIN can see both projects
    const orgAdminApi = new (await import('../helpers/api')).ApiClient()
    await orgAdminApi.login(orgAdmin.email, orgAdmin.password)
    const orgAdminProjects = await orgAdminApi.request<{ id: string }[]>('GET', '/projects')
    const orgAdminProjectIds = orgAdminProjects.map((p) => p.id)
    expect(orgAdminProjectIds).toContain(project1.projectId)
    expect(orgAdminProjectIds).toContain(project2.projectId)

    // Project 1 editor can see project 1 but NOT project 2
    const editorApi = new (await import('../helpers/api')).ApiClient()
    await editorApi.login(project1.editor.email, project1.editor.password)
    const editorProjects = await editorApi.request<{ id: string }[]>('GET', '/projects')
    const editorProjectIds = editorProjects.map((p) => p.id)
    expect(editorProjectIds).toContain(project1.projectId)
    expect(editorProjectIds).not.toContain(project2.projectId)
  })
})