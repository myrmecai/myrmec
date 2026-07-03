import { createFileRoute } from '@tanstack/react-router'
import { PlatformGroupLayout } from '@/components/platform-group-layout'

export const Route = createFileRoute('/_authenticated/platform/ai-infra')({
  component: () => (
    <PlatformGroupLayout
      title="AI Infrastructure"
      items={[
        { label: 'Models', to: '/platform/ai-infra/models' },
        { label: 'Model Providers', to: '/platform/ai-infra/providers' },
        { label: 'Agent Profiles', to: '/platform/ai-infra/agent-profiles' },
        { label: 'Agents', to: '/platform/ai-infra/agents' },
        { label: 'Tools', to: '/platform/ai-infra/tools' },
      ]}
    />
  ),
})