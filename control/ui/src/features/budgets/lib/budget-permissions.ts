// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { QuotaScope } from '@/lib/api'

interface AuthChecks {
  isPlatformAdmin: boolean
  hasSystemRole: (role: 'BUDGET_OWNER' | 'ORG_ADMIN' | 'PLATFORM_ADMIN') => boolean
  hasGroupRole: (groupId: string, role: 'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR') => boolean
  hasProjectRole: (projectId: string, role: 'BUDGET_OWNER' | 'PROJECT_OWNER' | 'EDITOR') => boolean
}

/**
 * Whether the current user can create a budget at the given scope.
 * Mirrors the backend {@code BudgetPermissions.canCreate*} rules.
 */
export function canCreateBudget(
  auth: AuthChecks,
  scopeType: QuotaScope,
  scopeId: string,
  groupId?: string,
): boolean {
  if (auth.isPlatformAdmin || auth.hasSystemRole('BUDGET_OWNER')) return true

  if (scopeType === 'GROUP') {
    return auth.hasGroupRole(scopeId, 'BUDGET_OWNER')
  }

  if (scopeType === 'PROJECT') {
    if (groupId && auth.hasGroupRole(groupId, 'BUDGET_OWNER')) return true
    return auth.hasProjectRole(scopeId, 'BUDGET_OWNER')
  }

  if (scopeType === 'SERVICE') {
    if (groupId && auth.hasGroupRole(groupId, 'BUDGET_OWNER')) return true
    if (auth.hasProjectRole(scopeId, 'BUDGET_OWNER')) return true
    return (
      auth.hasProjectRole(scopeId, 'PROJECT_OWNER') ||
      auth.hasProjectRole(scopeId, 'EDITOR')
    )
  }

  return false
}

/**
 * Whether the current user can edit / pause / resume / delete an existing
 * budget at the given scope.
 */
export function canMutateBudget(
  auth: AuthChecks,
  scopeType: QuotaScope,
  scopeId: string,
  groupId?: string,
): boolean {
  if (auth.isPlatformAdmin || auth.hasSystemRole('BUDGET_OWNER')) return true

  if (scopeType === 'GROUP') {
    return auth.hasGroupRole(scopeId, 'BUDGET_OWNER')
  }

  if (scopeType === 'PROJECT') {
    if (groupId && auth.hasGroupRole(groupId, 'BUDGET_OWNER')) return true
    return auth.hasProjectRole(scopeId, 'BUDGET_OWNER')
  }

  if (scopeType === 'SERVICE') {
    if (groupId && auth.hasGroupRole(groupId, 'BUDGET_OWNER')) return true
    if (auth.hasProjectRole(scopeId, 'BUDGET_OWNER')) return true
    return (
      auth.hasProjectRole(scopeId, 'PROJECT_OWNER') ||
      auth.hasProjectRole(scopeId, 'EDITOR')
    )
  }

  return false
}
