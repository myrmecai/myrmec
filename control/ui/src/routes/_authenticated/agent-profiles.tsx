import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/agent-profiles')({
  beforeLoad: () => { throw redirect({ to: '/platform/ai-infra/agent-profiles' }) },
})
