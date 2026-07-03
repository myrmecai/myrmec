import { createFileRoute } from '@tanstack/react-router'
import { PlatformGroupLayout } from '@/components/platform-group-layout'

export const Route = createFileRoute('/_authenticated/platform/security')({
  component: () => (
    <PlatformGroupLayout
      title="Security & Access"
      items={[
        { label: 'Global Secrets', to: '/platform/security/secrets' },
        { label: 'Auth Providers', to: '/platform/security/auth-providers' },
      ]}
    />
  ),
})