import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/')({
  beforeLoad: ({ context }) => {
    // Wait for auth to initialize
    if (context.auth.isLoading) {
      return
    }
    if (!context.auth.isAuthenticated) {
      throw redirect({ to: '/login' })
    }
    throw redirect({ to: '/dashboard' })
  },
})
