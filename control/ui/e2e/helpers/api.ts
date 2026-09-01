/**
 * Thin HTTP helper for arranging E2E test state via the engine REST API.
 *
 * Per the Phase 4 plan's "arrange-via-API, assert-via-UI" rule, tests use
 * this helper for data setup (login, project/KB creation, etc.) and reserve
 * the browser only for the actual assertion under test.
 */

const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:9090/api/v1'

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export class ApiClient {
  private accessToken: string | null = null

  /** Engine REST origin without the `/api/v1` suffix. Useful for actuator etc. */
  get rootOrigin(): string {
    return API_BASE.replace(/\/api\/v1\/?$/, '')
  }

  async login(email: string, password: string): Promise<LoginResponse> {
    const res = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    if (!res.ok) {
      throw new Error(`Login failed: ${res.status} ${await res.text()}`)
    }
    const body = (await res.json()) as LoginResponse
    this.accessToken = body.accessToken
    return body
  }

  /** Convenience wrapper that throws on non-2xx responses and returns JSON. */
  async request<T = unknown>(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<T> {
    const url = path.startsWith('http') ? path : `${API_BASE}${path}`
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    }
    if (this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`
    const res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`${method} ${path} -> ${res.status}: ${text}`)
    }
    if (res.status === 204) return undefined as T
    const text = await res.text()
    if (!text || text.trim().length === 0) return undefined as T
    return JSON.parse(text) as T
  }

  /**
   * Non-throwing request variant for asserting HTTP status codes.
   * Returns the status and parsed body (or raw text if not JSON).
   * Use this for RBAC and validation tests that need to assert 403/400/409.
   */
  async rawRequest(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<{ status: number; body: unknown }> {
    const url = path.startsWith('http') ? path : `${API_BASE}${path}`
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    }
    if (this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`
    const res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await res.text()
    let parsed: unknown = text
    if (text && text.trim().startsWith('{')) {
      try {
        parsed = JSON.parse(text)
      } catch {
        // Keep raw text if JSON parse fails
      }
    }
    return { status: res.status, body: parsed }
  }
}

/** Default admin credentials bootstrapped by `application-e2e.yaml`. */
export const E2E_ADMIN = {
  email: 'admin@e2e-test.local',
  password: 'E2eTest@123!',
} as const
