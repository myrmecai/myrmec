import { createContext, useContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, authApi, type LoginRequest, type LoginResponse, type SystemRole } from './api'

interface AuthUser {
  email: string
  roles: string[]
}

interface AuthContextType {
  user: AuthUser | null
  isAuthenticated: boolean
  isLoading: boolean
  login: (credentials: LoginRequest) => Promise<void>
  finalizeLogin: (response: LoginResponse) => void
  logout: () => void
  /** True if user holds the system-wide PLATFORM_ADMIN role. */
  isPlatformAdmin: boolean
  /** True if user holds the system-wide ORG_ADMIN role. */
  isOrgAdmin: boolean
  /** Check a system-wide role with implicit-role expansion. */
  hasSystemRole: (role: SystemRole) => boolean
  /** Check a role granted directly at the given group (no ancestor walk). */
  hasGroupRole: (groupId: string, role: SystemRole) => boolean
  /** Check a project-scoped role with implicit-role expansion. */
  hasProjectRole: (projectId: string, role: SystemRole) => boolean
}

const AuthContext = createContext<AuthContextType | null>(null)

const ACCESS_TOKEN_KEY = 'myrmec_access_token'
const REFRESH_TOKEN_KEY = 'myrmec_refresh_token'
const USER_KEY = 'myrmec_user'

function parseJwt(token: string): Record<string, unknown> {
  const base64Url = token.split('.')[1]
  const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
  const jsonPayload = decodeURIComponent(
    atob(base64)
      .split('')
      .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
      .join('')
  )
  return JSON.parse(jsonPayload)
}

function isTokenExpired(token: string): boolean {
  try {
    const payload = parseJwt(token)
    const exp = payload.exp as number
    return Date.now() >= exp * 1000
  } catch {
    return true
  }
}

/**
 * Roles implied by a granted role (including the role itself). Mirrors
 * {@code UserRole.Role.impliedRoles()} on the backend.
 *
 * <ul>
 *   <li>PROJECT_OWNER ⇒ EDITOR, VIEWER</li>
 *   <li>EDITOR ⇒ VIEWER</li>
 *   <li>APPROVER ⇒ VIEWER</li>
 *   <li>ORG_ADMIN ⇒ AUDITOR</li>
 * </ul>
 *
 * PLATFORM_ADMIN does <b>not</b> imply data-access roles.
 */
function impliedRoles(role: SystemRole): SystemRole[] {
  switch (role) {
    case 'PROJECT_OWNER':
      return ['PROJECT_OWNER', 'EDITOR', 'VIEWER']
    case 'EDITOR':
      return ['EDITOR', 'VIEWER']
    case 'APPROVER':
      return ['APPROVER', 'VIEWER']
    case 'ORG_ADMIN':
      return ['ORG_ADMIN', 'AUDITOR']
    default:
      return [role]
  }
}

interface ResolvedRoles {
  system: Set<SystemRole>
  byGroup: Map<string, Set<SystemRole>>
  byProject: Map<string, Set<SystemRole>>
}

/**
 * Parse JWT role claims (`sys:ROLE`, `grp:<id>:ROLE`, `proj:<id>:ROLE`) and
 * expand implicit roles per scope.
 */
function resolveRoles(roleClaims: string[] | undefined): ResolvedRoles {
  const system = new Set<SystemRole>()
  const byGroup = new Map<string, Set<SystemRole>>()
  const byProject = new Map<string, Set<SystemRole>>()
  if (!roleClaims) return { system, byGroup, byProject }

  for (const claim of roleClaims) {
    if (typeof claim !== 'string') continue
    if (claim.startsWith('sys:')) {
      const role = claim.slice(4) as SystemRole
      for (const r of impliedRoles(role)) system.add(r)
    } else if (claim.startsWith('grp:')) {
      const rest = claim.slice(4)
      const sep = rest.lastIndexOf(':')
      if (sep <= 0) continue
      const gid = rest.slice(0, sep)
      const role = rest.slice(sep + 1) as SystemRole
      let bucket = byGroup.get(gid)
      if (!bucket) {
        bucket = new Set()
        byGroup.set(gid, bucket)
      }
      for (const r of impliedRoles(role)) bucket.add(r)
    } else if (claim.startsWith('proj:')) {
      const rest = claim.slice(5)
      const sep = rest.lastIndexOf(':')
      if (sep <= 0) continue
      const pid = rest.slice(0, sep)
      const role = rest.slice(sep + 1) as SystemRole
      let bucket = byProject.get(pid)
      if (!bucket) {
        bucket = new Set()
        byProject.set(pid, bucket)
      }
      for (const r of impliedRoles(role)) bucket.add(r)
    }
  }
  return { system, byGroup, byProject }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const finalizeLogin = useCallback((response: LoginResponse) => {
    localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, response.refreshToken)
    api.setAccessToken(response.accessToken)

    const payload = parseJwt(response.accessToken)
    const newUser: AuthUser = {
      email: (payload.email as string) || (payload.sub as string),
      roles: (payload.roles as string[]) || [],
    }
    setUser(newUser)
    localStorage.setItem(USER_KEY, JSON.stringify(newUser))
  }, [])

  // Initialize auth state from storage
  useEffect(() => {
    const initAuth = async () => {
      const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
      const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
      const storedUser = localStorage.getItem(USER_KEY)

      if (!accessToken || !refreshToken) {
        setIsLoading(false)
        return
      }

      // Check if access token is expired
      if (isTokenExpired(accessToken)) {
        // Try to refresh
        try {
          const response = await authApi.refresh({ refreshToken })
          localStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken)
          api.setAccessToken(response.accessToken)

          // Parse user from new token
          const payload = parseJwt(response.accessToken)
          const newUser: AuthUser = {
            email: (payload.email as string) || (payload.sub as string),
            roles: (payload.roles as string[]) || [],
          }
          setUser(newUser)
          localStorage.setItem(USER_KEY, JSON.stringify(newUser))
        } catch {
          // Refresh failed, clear auth
          localStorage.removeItem(ACCESS_TOKEN_KEY)
          localStorage.removeItem(REFRESH_TOKEN_KEY)
          localStorage.removeItem(USER_KEY)
        }
      } else {
        // Token still valid
        api.setAccessToken(accessToken)
        if (storedUser) {
          setUser(JSON.parse(storedUser))
        }
      }

      setIsLoading(false)
    }

    initAuth()
  }, [])

  const login = useCallback(async (credentials: LoginRequest) => {
    const response: LoginResponse = await authApi.login(credentials)
    finalizeLogin(response)
  }, [finalizeLogin])

  const logout = useCallback(() => {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    api.setAccessToken(null)
    setUser(null)
  }, [])

  const resolved = useMemo(() => resolveRoles(user?.roles), [user?.roles])

  const hasSystemRole = useCallback(
    (role: SystemRole) => resolved.system.has(role),
    [resolved],
  )
  const hasGroupRole = useCallback(
    (groupId: string, role: SystemRole) => resolved.byGroup.get(groupId)?.has(role) ?? false,
    [resolved],
  )
  const hasProjectRole = useCallback(
    (projectId: string, role: SystemRole) =>
      resolved.byProject.get(projectId)?.has(role) ?? false,
    [resolved],
  )

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        finalizeLogin,
        logout,
        isPlatformAdmin: resolved.system.has('PLATFORM_ADMIN'),
        isOrgAdmin: resolved.system.has('ORG_ADMIN'),
        hasSystemRole,
        hasGroupRole,
        hasProjectRole,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
