import type { Page } from '@playwright/test'
import { ApiClient, E2E_ADMIN } from './api'

/**
 * Storage keys used by the React app's auth context. Must match
 * {@code ACCESS_TOKEN_KEY} / {@code REFRESH_TOKEN_KEY} / {@code USER_KEY}
 * in {@code src/lib/auth.tsx}.
 */
const ACCESS_TOKEN_KEY = 'myrmec_access_token'
const REFRESH_TOKEN_KEY = 'myrmec_refresh_token'
const USER_KEY = 'myrmec_user'

interface JwtPayload {
  sub?: string
  email?: string
  roles?: string[]
  exp?: number
}

/**
 * Decode the payload of a JWT without verifying its signature — only meant for
 * the test harness, which obtains tokens directly from the engine.
 */
function decodeJwt(token: string): JwtPayload {
  const parts = token.split('.')
  if (parts.length < 2) {
    throw new Error('Malformed JWT')
  }
  const padded = parts[1]
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(parts[1].length + ((4 - (parts[1].length % 4)) % 4), '=')
  const json = Buffer.from(padded, 'base64').toString('utf-8')
  return JSON.parse(json) as JwtPayload
}

/**
 * Authenticates against the REST API and seeds the browser's localStorage
 * with the resulting tokens — bypasses the visual login flow so individual
 * specs aren't paying the form-fill cost on every test.
 *
 * <p>Call this BEFORE navigating to an authenticated route; the React auth
 * context reads localStorage during initial render and route guards check
 * {@code !!user} via {@code isAuthenticated}.
 */
export async function loginAsAdmin(page: Page): Promise<void> {
  const api = new ApiClient()
  const { accessToken, refreshToken } = await api.login(
    E2E_ADMIN.email,
    E2E_ADMIN.password,
  )
  const payload = decodeJwt(accessToken)
  const user = {
    email: payload.email ?? payload.sub ?? E2E_ADMIN.email,
    roles: payload.roles ?? [],
  }

  // Use addInitScript to seed localStorage before any page loads. This
  // ensures the auth context reads the token on the very first render,
  // before route guards run, avoiding the race condition where the
  // _authenticated guard sees isLoading=true and isAuthenticated=false.
  await page.addInitScript(
    ([access, refresh, userJson, accessKey, refreshKey, userKey]) => {
      window.localStorage.setItem(accessKey, access)
      window.localStorage.setItem(refreshKey, refresh)
      window.localStorage.setItem(userKey, userJson)
    },
    [
      accessToken,
      refreshToken,
      JSON.stringify(user),
      ACCESS_TOKEN_KEY,
      REFRESH_TOKEN_KEY,
      USER_KEY,
    ] as const,
  )
}
