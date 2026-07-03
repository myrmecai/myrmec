import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/admin/secrets')({
  beforeLoad: () => { throw redirect({ to: '/platform/security/secrets' }) },
})
