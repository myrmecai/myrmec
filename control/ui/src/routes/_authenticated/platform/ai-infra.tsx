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
        { label: 'Agent Hosts', to: '/platform/ai-infra/agent-hosts' },
        { label: 'Tools', to: '/platform/ai-infra/tools' },
      ]}
    />
  ),
})