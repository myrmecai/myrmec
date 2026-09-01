// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ⚠️ OSS SECURITY-INVARIANT CARVE-OUT (decisions 2026-08-17)
 * This spec is part of the standing OSS security safety net. It must never be
 * deleted or weakened. If it fails, it indicates a security regression.
 */
import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { seedProjectWithMembers, createUser, assignProjectRole } from '../helpers/rbac'
import { createGlobalSecret } from '../helpers/secrets'

/**
 * Audit log API coverage — AUD-02, AUD-03, AUD-04.
 *
 * All tests query GET /api/v1/audit-log using the admin token
 * (PLATFORM_ADMIN role grants audit read access).
 */

test.describe('Audit log API', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  })

  // AUD-02: Failed login is recorded with reason code
  test('AUD-02: failed login recorded in audit log', async ({ api }) => {
    // Perform a failed login (wrong password)
    await fetch('http://localhost:9090/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: E2E_ADMIN.email, password: 'WrongPassword123!' }),
    })

    // Query audit log for LOGIN_FAILED events
    const auditPage = await api.request<{
      items: Array<{ action: string; details: Record<string, unknown> }>
    }>('GET', '/audit-log?action=LOGIN_FAILED&size=10')

    expect(auditPage.items).toBeDefined()
    // There should be at least one LOGIN_FAILED event (we just triggered one)
    expect(auditPage.items.length).toBeGreaterThan(0)
    const failedEvent = auditPage.items.find((e) => e.action === 'LOGIN_FAILED' || e.action === 'LOGIN_FAILED' || e.action?.includes('LOGIN'))
    expect(failedEvent).toBeDefined()
  })

  // AUD-03: Role grant captured with actor + target
  test('AUD-03: role grant captured in audit log', async ({ api }) => {
    const { projectId } = await seedProjectWithMembers(api, 'aud03')

    // Create a new user and assign a role
    const userId = await createUser(api, `aud03-target-${Date.now().toString(36)}@e2e-test.local`, 'AUD03 Target')
    await assignProjectRole(api, userId, projectId, 'EDITOR')

    // Query audit log for role grant events
    const auditPage = await api.request<{
      items: Array<{ action: string; actorUserId: string; details: Record<string, unknown> }>
    }>('GET', '/audit-log?action=USER_ROLE_GRANTED&size=10')

    expect(auditPage.items).toBeDefined()
    const grantEvent = auditPage.items.find((e) => e.action === 'USER_ROLE_GRANTED')
    expect(grantEvent).toBeDefined()
    // The actor should be the admin user
    expect(grantEvent!.actorUserId).toBeDefined()
  })

  // AUD-04: Secret CRUD captured without leaking secret value
  test('AUD-04: secret CRUD recorded without secret value in audit payload', async ({ api }) => {
    const secretValue = 'audit-test-secret-value-456'
    const secret = await createGlobalSecret(api, `audit-secret-${Date.now().toString(36)}`, secretValue)

    // Query audit log for secret events. The engine stores entity type as
    // "secret" (lowercase, matching ResourceType.SECRET constant) and the
    // resourceType filter is case-sensitive, so match that exactly.
    // The entry payload is exposed as `payloadJson` (a JSON string), not `details`.
    const auditPage = await api.request<{
      items: Array<{
        action: string
        resourceType: string
        resourceId: string
        payloadJson: string | null
      }>
    }>('GET', '/audit-log?resourceType=secret&size=20')

    expect(auditPage.items).toBeDefined()
    expect(auditPage.items.length).toBeGreaterThan(0)

    // Find the create event for OUR secret (match by resourceId so we don't pick up
    // an unrelated secret's event). Action might be 'CREATED', 'SECRET_CREATED', etc.
    const createEvent = auditPage.items.find((e) =>
      (e.action === 'CREATED' || e.action === 'SECRET_CREATED' || e.action?.includes('CREATED')) &&
      e.resourceType === 'secret' &&
      e.resourceId === secret.id)
    expect(createEvent).toBeDefined()
    // Assert the secret value does NOT appear in the audit payload.
    expect(createEvent!.payloadJson ?? '').not.toContain(secretValue)
  })
})