import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/tools')({
  beforeLoad: () => { throw redirect({ to: '/platform/ai-infra/tools' }) },
})
