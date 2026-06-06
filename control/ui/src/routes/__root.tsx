import { createRootRouteWithContext, Outlet, useRouter } from '@tanstack/react-router'
import { TanStackRouterDevtools } from '@tanstack/router-devtools'
import type { QueryClient } from '@tanstack/react-query'
import { useAuth, type useAuth as UseAuthType } from '@/lib/auth'
import { useEffect, useRef } from 'react'

interface RouterContext {
  auth: ReturnType<typeof UseAuthType>
  queryClient: QueryClient
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootComponent,
})

function RootComponent() {
  // Use the hook directly to get reactive updates
  const auth = useAuth()
  const router = useRouter()
  const wasLoading = useRef(true)
  
  // When loading finishes, invalidate router to re-evaluate routes
  useEffect(() => {
    if (wasLoading.current && !auth.isLoading) {
      wasLoading.current = false
      router.invalidate()
    }
  }, [auth.isLoading, router])
  
  // Show loading while auth state is being restored
  if (auth.isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    )
  }

  return (
    <>
      <Outlet />
      <TanStackRouterDevtools position="bottom-right" />
    </>
  )
}
