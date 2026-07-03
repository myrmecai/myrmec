import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/admin/providers')({
  beforeLoad: () => { throw redirect({ to: '/platform/ai-infra/providers' }) },
})
