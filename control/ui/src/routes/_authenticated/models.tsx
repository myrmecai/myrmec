import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/models')({
  beforeLoad: () => { throw redirect({ to: '/platform/ai-infra/models' }) },
})
