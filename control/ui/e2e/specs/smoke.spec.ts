import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

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
})
