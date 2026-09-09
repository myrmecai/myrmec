import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import * as http from 'node:http'

/**
 * Phase 4a smoke spec: proves the full E2E harness is wired correctly.
 *
 * <ul>
 *   <li>The engine boots with the {@code e2e} profile and auto-creates the
 *       admin user.</li>
 *   <li>The Vite dev server proxies {@code /api/*} to the engine.</li>
 *   <li>The Playwright fixture authenticates against {@code /auth/login}
 *       and seeds tokens into localStorage.</li>
 *   <li>The React app reads the tokens and renders an authenticated route.</li>
 * </ul>
 *
 * Feature-specific specs (chat, approvals, RAG) land in later phases.
 */
test.describe('Phase 4a harness smoke', () => {
  test('engine actuator is reachable through the test fixture API client', async ({
    api,
  }) => {
    // The fixture is unauthenticated; actuator/health is public.
    const res = await fetch(`${api.rootOrigin}/actuator/health`)
    expect(res.ok).toBeTruthy()
    const body = (await res.json()) as { status: string }
    expect(body.status).toBe('UP')
  })

  test('admin can log in via API and reach the dashboard', async ({
    adminPage,
  }) => {
    await adminPage.goto('/dashboard')
    await expect(
      adminPage.getByRole('heading', { name: 'Dashboard' }),
    ).toBeVisible()
  })

  test('login form rejects unknown credentials', async ({ page }) => {
    await page.goto('/login')
    // Use the same login API directly; this asserts the engine path without
    // exercising the visual form (kept simple at Phase 4a — UI form coverage
    // grows in feature phases that touch the login page).
    //
    // Engine returns HTTP 400 (BadRequestException → "Invalid email or
    // password") rather than 401 for failed credentials. That is the current
    // contract enforced by GlobalExceptionHandler; if it ever flips to 401
    // this assertion must move in lock-step.
    const res = await fetch(`http://localhost:9090/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: E2E_ADMIN.email,
        password: 'definitely-wrong',
      }),
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as { errorCode?: string }
    expect(body.errorCode).toBe('BAD_REQUEST')
  })

  /**
   * Desktop-client login handoff (VS Code plugin), browser side:
   * /login?redirectUri=<loopback>&state=<nonce> captures the handoff,
   * a LOCAL form login redeems the fresh JWT for a one-time code, and
   * the browser redirects to the loopback listener with code + state.
   * The plugin then exchanges the code once (second use rejected).
   */
  test('login page hands a one-time code to the plugin loopback listener', async ({
    page,
  }) => {
    // The plugin's loopback listener: an ephemeral-port HTTP server
    // that captures the handoff and closes after the first request.
    const STATE = 'e2e-handoff-state'
    const server = http.createServer()
    const listenPort = new Promise<number>((resolve) => {
      server.once('listening', () =>
        resolve(server.address() !== null && typeof server.address() === 'object'
          ? (server.address() as { port: number }).port
          : 0),
      )
    })
    const captured = new Promise<{ code: string; state: string }>((resolve) => {
      server.once('request', (req, res) => {
        const url = new URL(req.url ?? '/', 'http://127.0.0.1')
        resolve({
          code: url.searchParams.get('code') ?? '',
          state: url.searchParams.get('state') ?? '',
        })
        res.writeHead(200, { 'Content-Type': 'text/plain' })
        res.end('ok')
        // One-shot listener — the real plugin closes after capture.
        server.close()
      })
    })
    server.listen(0, '127.0.0.1')
    const port = await listenPort

    try {
      await page.goto(
        `/login?redirectUri=http://127.0.0.1:${port}/callback&state=${STATE}`,
      )
      await page.locator('#email').fill(E2E_ADMIN.email)
      await page.locator('#password').fill(E2E_ADMIN.password)
      await page.getByRole('button', { name: 'Sign in' }).click()

      const handoff = await captured
      expect(handoff.state).toBe(STATE)
      expect(handoff.code).toMatch(/^myr_auth_/)

      // The plugin side of the loop: exchange once (200 + tokens),
      // exchange again (single-use CAS rejects the replay).
      const exchange = (code: string) =>
        fetch(`http://localhost:9090/api/v1/auth/code/exchange`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            code,
            redirectUri: `http://127.0.0.1:${port}/callback`,
          }),
        })

      const first = await exchange(handoff.code)
      expect(first.status).toBe(200)
      const tokens = (await first.json()) as { accessToken: string }
      expect(tokens.accessToken).toBeTruthy()

      const replay = await exchange(handoff.code)
      expect(replay.status).toBe(401)
    } finally {
      server.close()
    }
  })
})
