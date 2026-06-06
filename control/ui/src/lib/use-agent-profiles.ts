import { useQuery } from '@tanstack/react-query'
import { agentProfilesApi, type AgentProfile } from '@/lib/api'

/**
 * Shared cached query for active agent profiles. Used by the workflow editor
 * (step inspector) and any future picker. Five-minute stale time prevents
 * re-fetches when navigating between routes during a single authoring session.
 */
export function useAgentProfiles() {
  return useQuery<AgentProfile[]>({
    queryKey: ['agent-profiles', { active: true }],
    queryFn: () => agentProfilesApi.list(true),
    staleTime: 5 * 60 * 1000,
  })
}
