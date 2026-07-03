import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/auth-providers')({
  beforeLoad: () => { throw redirect({ to: '/platform/security/auth-providers' }) },
})
