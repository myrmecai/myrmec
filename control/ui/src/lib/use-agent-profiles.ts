import { useQuery } from '@tanstack/react-query'
import { agentProfilesApi, type AgentProfile } from '@/lib/api'

/**
 * Shared cached query for active agent profiles (public projection —
 * authenticated non-admin roster). Used by the workflow editor and run
 * views, which load as project members; the admin list is only for the
 * admin surfaces. Five-minute stale time prevents re-fetches when
 * navigating between routes during a single authoring session.
 */
export function useAgentProfiles() {
  return useQuery<AgentProfile[]>({
    queryKey: ['agent-profiles', { active: true, projection: 'public' }],
    queryFn: () => agentProfilesApi.listPublic(true),
    staleTime: 5 * 60 * 1000,
  })
}
