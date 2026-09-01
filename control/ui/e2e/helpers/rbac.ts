// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * RBAC arrange helpers for E2E tests that need project-scoped users
 * with specific roles (PROJECT_OWNER, EDITOR, VIEWER).
 *
 * All helpers use the admin API client (PLATFORM_ADMIN) for setup.
 * Tests then log in as the individual users to assert role-gated behavior.
 */

import { ApiClient } from './api'

export interface E2EUser {
  id: string
  email: string
  password: string
  name: string
}

export interface ProjectWithMembers {
  projectId: string
  projectName: string
  owner: E2EUser
  editor: E2EUser
  viewer: E2EUser
  approver?: E2EUser
  auditor?: E2EUser
}

/** Default password for E2E test users (meets policy: 8-50 chars, letter + digit + special). */
const DEFAULT_PASSWORD = 'TestPass123!'

/**
 * Assign a system-scoped role to a user.
 */
export async function assignSystemRole(
  api: ApiClient,
  userId: string,
  role: 'PLATFORM_ADMIN' | 'ORG_ADMIN' | 'BUDGET_OWNER' | 'APPROVER' | 'AUDITOR' | 'EDITOR' | 'VIEWER',
): Promise<void> {
  await api.request('POST', `/admin/users/${userId}/roles`, {
    role,
    scopeType: 'SYSTEM',
  })
}

/**
 * Create a LOCAL user and assign a system-scoped role.
 * Returns the user credentials.
 */
export async function seedOrgUser(
  api: ApiClient,
  prefix: string,
  role: 'PLATFORM_ADMIN' | 'ORG_ADMIN' | 'BUDGET_OWNER' | 'APPROVER' | 'AUDITOR' | 'EDITOR' | 'VIEWER',
): Promise<E2EUser> {
  const suffix = Date.now().toString(36)
  const email = `${prefix}-${role.toLowerCase()}-${suffix}@e2e-test.local`
  const name = `${prefix} ${role} ${suffix}`
  const id = await createUser(api, email, name)
  await assignSystemRole(api, id, role)
  return { id, email, password: DEFAULT_PASSWORD, name }
}

/**
 * Create a BUDGET_OWNER system user.
 */
export async function createBudgetOwner(api: ApiClient, prefix: string): Promise<E2EUser> {
  return seedOrgUser(api, prefix, 'BUDGET_OWNER')
}

/**
 * Create an ORG_ADMIN system user.
 */
export async function createOrgAdmin(api: ApiClient, prefix: string): Promise<E2EUser> {
  return seedOrgUser(api, prefix, 'ORG_ADMIN')
}

/**
 * Create a group with a member assigned a group-scoped role.
 */
export async function seedGroupWithMember(
  api: ApiClient,
  prefix: string,
  userId: string,
  role: 'ORG_ADMIN' | 'BUDGET_OWNER' | 'APPROVER' | 'AUDITOR' | 'EDITOR' | 'VIEWER',
): Promise<{ groupId: string; groupName: string }> {
  const suffix = Date.now().toString(36)
  const groupName = `${prefix}-grp-${suffix}`
  const group = await api.request<{ id: string }>('POST', '/admin/groups', { name: groupName })
  await api.request('POST', `/admin/users/${userId}/roles`, {
    role,
    scopeType: 'GROUP',
    groupId: group.id,
  })
  return { groupId: group.id, groupName }
}

/**
 * Create a LOCAL user via the admin API.
 * Returns the user ID.
 */
export async function createUser(
  api: ApiClient,
  email: string,
  name: string,
  password: string = DEFAULT_PASSWORD,
): Promise<string> {
  const res = await api.request<{ id: string }>('POST', '/admin/users', {
    email,
    name,
    providerCode: 'LOCAL',
    password,
  })
  return res.id
}

/**
 * Assign a project-scoped role to a user.
 */
export async function assignProjectRole(
  api: ApiClient,
  userId: string,
  projectId: string,
  role: 'PROJECT_OWNER' | 'EDITOR' | 'VIEWER' | 'APPROVER' | 'AUDITOR',
): Promise<void> {
  await api.request('POST', `/admin/users/${userId}/roles`, {
    role,
    scopeType: 'PROJECT',
    projectId,
  })
}

/**
 * Create a project with three members: owner, editor, viewer.
 * The admin API client must already be authenticated as admin.
 *
 * Returns the project ID and user credentials for each role.
 */
export async function seedProjectWithMembers(
  api: ApiClient,
  prefix: string,
  options?: { allowedServiceTypes?: string[]; includeApprover?: boolean; includeAuditor?: boolean },
): Promise<ProjectWithMembers> {
  const suffix = Date.now().toString(36)

  // Create project
  const projectBody: Record<string, unknown> = {
    name: `${prefix}-${suffix}`,
  }
  if (options?.allowedServiceTypes) {
    projectBody.allowedServiceTypes = options.allowedServiceTypes
  }
  const project = await api.request<{ id: string }>('POST', '/projects', projectBody)

  // Create users
  const ownerEmail = `${prefix}-owner-${suffix}@e2e-test.local`
  const editorEmail = `${prefix}-editor-${suffix}@e2e-test.local`
  const viewerEmail = `${prefix}-viewer-${suffix}@e2e-test.local`

  const ownerId = await createUser(api, ownerEmail, `${prefix} Owner ${suffix}`)
  const editorId = await createUser(api, editorEmail, `${prefix} Editor ${suffix}`)
  const viewerId = await createUser(api, viewerEmail, `${prefix} Viewer ${suffix}`)

  // Assign roles
  await assignProjectRole(api, ownerId, project.id, 'PROJECT_OWNER')
  await assignProjectRole(api, editorId, project.id, 'EDITOR')
  await assignProjectRole(api, viewerId, project.id, 'VIEWER')

  // Create approver and auditor if requested
  let approver: E2EUser | undefined
  let auditor: E2EUser | undefined
  if (options?.includeApprover) {
    const approverEmail = `${prefix}-approver-${suffix}@e2e-test.local`
    const approverId = await createUser(api, approverEmail, `${prefix} Approver ${suffix}`)
    await assignProjectRole(api, approverId, project.id, 'APPROVER')
    approver = { id: approverId, email: approverEmail, password: DEFAULT_PASSWORD, name: `${prefix} Approver` }
  }
  if (options?.includeAuditor) {
    const auditorEmail = `${prefix}-auditor-${suffix}@e2e-test.local`
    const auditorId = await createUser(api, auditorEmail, `${prefix} Auditor ${suffix}`)
    await assignProjectRole(api, auditorId, project.id, 'AUDITOR')
    auditor = { id: auditorId, email: auditorEmail, password: DEFAULT_PASSWORD, name: `${prefix} Auditor` }
  }

  return {
    projectId: project.id,
    projectName: `${prefix}-${suffix}`,
    owner: { id: ownerId, email: ownerEmail, password: DEFAULT_PASSWORD, name: `${prefix} Owner` },
    editor: { id: editorId, email: editorEmail, password: DEFAULT_PASSWORD, name: `${prefix} Editor` },
    viewer: { id: viewerId, email: viewerEmail, password: DEFAULT_PASSWORD, name: `${prefix} Viewer` },
    approver,
    auditor,
  }
}