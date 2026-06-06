import { createFileRoute, redirect, useRouter } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import { useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ApiRequestError, authApi, type EnabledAuthProvider } from '@/lib/api'
import {
  clearExternalCallbackParams,
  consumeExternalAuthProvider,
  readExternalCallbackParams,
  registerExternalAuthState,
} from '@/lib/external-auth'

export const Route = createFileRoute('/login')({
  beforeLoad: ({ context }) => {
    // Wait for auth to initialize
    if (context.auth.isLoading) {
      return
    }
    if (context.auth.isAuthenticated) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: LoginPage,
})

function LoginPage() {
  const { login, finalizeLogin, isAuthenticated } = useAuth()
  const router = useRouter()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [externalLoadingProvider, setExternalLoadingProvider] = useState<string | null>(null)
  const [providers, setProviders] = useState<EnabledAuthProvider[]>([])
  const processingExternalStateRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    const loadProviders = async () => {
      try {
        const enabledProviders = await authApi.listEnabledProviders()
        setProviders(enabledProviders)
      } catch {
        setProviders([])
      }
    }

    loadProviders()
  }, [])

  useEffect(() => {
    if (isAuthenticated) {
      router.navigate({ to: '/dashboard', replace: true })
    }
  }, [isAuthenticated, router])

  useEffect(() => {
    const completeExternalCallback = async () => {
      const { state, code, error: callbackError, errorDescription } =
        readExternalCallbackParams(window.location.search)

      if (callbackError) {
        setError(errorDescription || 'External login failed')
        clearExternalCallbackParams()
        return
      }

      if (!state || !code) {
        return
      }

      if (processingExternalStateRef.current.has(state)) {
        return
      }
      processingExternalStateRef.current.add(state)

      const providerCode = consumeExternalAuthProvider(state)
      if (!providerCode) {
        if (!isAuthenticated) {
          setError('Unable to complete external login. Please try again.')
        }
        clearExternalCallbackParams()
        processingExternalStateRef.current.delete(state)
        return
      }

      setExternalLoadingProvider(providerCode)
      setError(null)

      try {
        const redirectUri = `${window.location.origin}/login`
        const response = await authApi.completeExternalLogin(providerCode, state, code, redirectUri)
        finalizeLogin(response)
        clearExternalCallbackParams()
      } catch (err) {
        if (err instanceof ApiRequestError) {
          setError(err.error.message)
        } else {
          setError('External login callback failed')
        }
        clearExternalCallbackParams()
        setExternalLoadingProvider(null)
      } finally {
        processingExternalStateRef.current.delete(state)
      }
    }

    completeExternalCallback()
  }, [finalizeLogin, router])

  const externalProviders = providers.filter((provider) => provider.providerType !== 'LOCAL')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (isLoading) {
      return
    }
    setError(null)
    setIsLoading(true)

    try {
      await login({ email, password })
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.error.message)
      } else {
        setError('An unexpected error occurred')
      }
    } finally {
      setIsLoading(false)
    }
  }

  const handleExternalLogin = async (providerCode: string) => {
    setError(null)
    setExternalLoadingProvider(providerCode)
    try {
      const redirectUri = `${window.location.origin}/login`
      const response = await authApi.startExternalLogin(providerCode, redirectUri)
      registerExternalAuthState(response.state, response.providerCode)
      window.location.assign(response.authorizationUrl)
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.error.message)
      } else {
        setError('Failed to start external login')
      }
      setExternalLoadingProvider(null)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-muted/40">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">Myrmec Control Plane</CardTitle>
          <CardDescription>Sign in to your account</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            {error && (
              <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
                {error}
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                placeholder="admin@myrmec.local"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoComplete="email"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete="current-password"
              />
            </div>
            <Button type="submit" className="w-full" disabled={isLoading}>
              {isLoading ? 'Signing in...' : 'Sign in'}
            </Button>

            {externalProviders.length > 0 && (
              <>
                <div className="relative py-2">
                  <div className="absolute inset-0 flex items-center">
                    <span className="w-full border-t" />
                  </div>
                  <div className="relative flex justify-center text-xs uppercase">
                    <span className="bg-background px-2 text-muted-foreground">Or continue with</span>
                  </div>
                </div>
                <div className="space-y-2">
                  {externalProviders.map((provider) => (
                    <Button
                      key={provider.code}
                      type="button"
                      variant="outline"
                      className="w-full"
                      disabled={!!externalLoadingProvider}
                      onClick={() => handleExternalLogin(provider.code)}
                    >
                      {externalLoadingProvider === provider.code
                        ? `Redirecting to ${provider.name}...`
                        : `Continue with ${provider.name}`}
                    </Button>
                  ))}
                </div>
              </>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
