// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useQuery } from '@tanstack/react-query'
import { governanceApi, type GovernanceProfile } from '@/lib/api'
import { GovernancePolicy } from './GovernancePolicy'

/**
 * React hook that provides the current governance policy.
 *
 * TanStack Query: `['governance-profile-current']`. The query is invalidated
 * by `GovernanceProfilesList.tsx` when the default profile changes (T5).
 *
 * **Loading strategy:** optimistic — controls default to **enabled** while
 * the policy loads. The backend 403 is the real enforcement (defense-in-depth).
 * If the query fails, we fall back to a permissive null policy (everything
 * enabled) — the backend 403 still protects.
 *
 * **Forward-compat:** `scope?` param is reserved for the future project-scoped
 * endpoint; V1 reads the org default.
 */
export function useGovernancePolicy() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['governance-profile-current'],
    queryFn: () => governanceApi.getCurrent(),
    staleTime: 0, // Always refetch so governance changes are immediately visible
  })

  const policy = data ? new GovernancePolicy(data) : null

  return {
    policy,
    isLoading,
    error,
    /** The profile name for display; null while loading or on error. */
    profileName: data?.name ?? null,
    /** The raw API response, for the compare-matrix page that needs groups. */
    rawProfile: data as GovernanceProfile | null,
  }
}