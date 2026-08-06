// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { canCreateBudget, canMutateBudget } from './budget-permissions'

function makeAuth(overrides: {
  isPlatformAdmin?: boolean
  system?: Array<'BUDGET_OWNER' | 'ORG_ADMIN' | 'PLATFORM_ADMIN'>
  group?: Record<string, Array<'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR'>>
  project?: Record<string, Array<'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR'>>
} = {}) {
  return {
    isPlatformAdmin: overrides.isPlatformAdmin ?? false,
    hasSystemRole: (role: 'BUDGET_OWNER' | 'ORG_ADMIN' | 'PLATFORM_ADMIN') =>
      overrides.system?.includes(role) ?? false,
    hasGroupRole: (groupId: string, role: 'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR') =>
      overrides.group?.[groupId]?.includes(role) ?? false,
    hasProjectRole: (projectId: string, role: 'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR') =>
      overrides.project?.[projectId]?.includes(role) ?? false,
  }
}

describe('budget permissions', () => {
  it('platform admin can create and mutate at any scope', () => {
    const auth = makeAuth({ isPlatformAdmin: true })
    expect(canCreateBudget(auth, 'ORG', '')).toBe(true)
    expect(canMutateBudget(auth, 'PROJECT', 'p1')).toBe(true)
  })

  it('system BUDGET_OWNER can create and mutate at any scope', () => {
    const auth = makeAuth({ system: ['BUDGET_OWNER'] })
    expect(canCreateBudget(auth, 'GROUP', 'g1')).toBe(true)
    expect(canMutateBudget(auth, 'SERVICE', 'p1')).toBe(true)
  })

  it('ORG budget owner cannot create/mutate', () => {
    const auth = makeAuth({ system: ['ORG_ADMIN'] })
    expect(canCreateBudget(auth, 'ORG', '')).toBe(false)
    expect(canMutateBudget(auth, 'GROUP', 'g1')).toBe(false)
  })

  describe('group scope', () => {
    it('allows group BUDGET_OWNER', () => {
      const auth = makeAuth({ group: { g1: ['BUDGET_OWNER'] } })
      expect(canCreateBudget(auth, 'GROUP', 'g1')).toBe(true)
      expect(canMutateBudget(auth, 'GROUP', 'g1')).toBe(true)
    })

    it('denies other group members', () => {
      const auth = makeAuth({ group: { g1: ['PROJECT_OWNER'] } })
      expect(canCreateBudget(auth, 'GROUP', 'g1')).toBe(false)
      expect(canMutateBudget(auth, 'GROUP', 'g1')).toBe(false)
    })
  })

  describe('project scope', () => {
    it('allows project BUDGET_OWNER', () => {
      const auth = makeAuth({ project: { p1: ['BUDGET_OWNER'] } })
      expect(canCreateBudget(auth, 'PROJECT', 'p1')).toBe(true)
      expect(canMutateBudget(auth, 'PROJECT', 'p1')).toBe(true)
    })

    it('allows via group BUDGET_OWNER', () => {
      const auth = makeAuth({ group: { g1: ['BUDGET_OWNER'] } })
      expect(canCreateBudget(auth, 'PROJECT', 'p1', 'g1')).toBe(true)
      expect(canMutateBudget(auth, 'PROJECT', 'p1', 'g1')).toBe(true)
    })

    it('denies project EDITOR/PROJECT_OWNER', () => {
      const auth = makeAuth({ project: { p1: ['EDITOR', 'PROJECT_OWNER'] } })
      expect(canCreateBudget(auth, 'PROJECT', 'p1')).toBe(false)
      expect(canMutateBudget(auth, 'PROJECT', 'p1')).toBe(false)
    })
  })

  describe('service scope', () => {
    it('allows project BUDGET_OWNER', () => {
      const auth = makeAuth({ project: { p1: ['BUDGET_OWNER'] } })
      expect(canCreateBudget(auth, 'SERVICE', 'p1')).toBe(true)
      expect(canMutateBudget(auth, 'SERVICE', 'p1')).toBe(true)
    })

    it('allows project EDITOR/PROJECT_OWNER', () => {
      const auth = makeAuth({ project: { p1: ['EDITOR'] } })
      expect(canCreateBudget(auth, 'SERVICE', 'p1')).toBe(true)
      const auth2 = makeAuth({ project: { p1: ['PROJECT_OWNER'] } })
      expect(canCreateBudget(auth2, 'SERVICE', 'p1')).toBe(true)
    })

    it('allows via group BUDGET_OWNER', () => {
      const auth = makeAuth({ group: { g1: ['BUDGET_OWNER'] } })
      expect(canCreateBudget(auth, 'SERVICE', 'p1', 'g1')).toBe(true)
    })

    it('denies plain project VIEWER role (not EDITOR)', () => {
      const auth = makeAuth({ project: { p1: [] } })
      expect(canCreateBudget(auth, 'SERVICE', 'p1')).toBe(false)
    })
  })
})
