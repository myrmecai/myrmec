// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Desktop-client login handoff (VS Code plugin).
 *
 * The plugin opens `/login?redirectUri=<its loopback listener>&state=<nonce>`.
 * After ANY successful login path (LOCAL form, external-provider callback),
 * the page redeems the fresh JWT for a one-time authorization code
 * (`POST /auth/authorize-code`) and redirects the browser to the loopback
 * listener with the code. The plugin validates `state`, then exchanges the
 * code for its own token pair — the page never learns anything it must
 * keep beyond the handoff, and the password never leaves the browser.
 *
 * The pending handoff lives in sessionStorage so it survives the
 * external-provider round-trip (GitHub/Google/OIDC redirect back to
 * `/login?code=…` — the plugin's redirectUri must outlive that hop).
 */

const PENDING_HANDOFF_KEY = 'myrmec_desktop_handoff'

export interface DesktopHandoff {
  /** The plugin's loopback callback (http://127.0.0.1:<port>/…). */
  redirectUri: string
  /** Plugin-generated nonce; echoed back so the plugin can validate. */
  state: string
}

/** A loopback http URL with an explicit port — mirrors the engine's check. */
function isLoopbackHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    if (url.protocol !== 'http:') return false
    if (!['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname)) return false
    return url.port !== ''
  } catch {
    return false
  }
}

/**
 * Capture a pending desktop handoff from the /login URL. Reads
 * `redirectUri` and `state`, validates the redirect target, and
 * stashes the handoff in sessionStorage (the URL param may be lost
 * during the external-provider round-trip).
 */
export function captureDesktopHandoff(search: string): void {
  const params = new URLSearchParams(search)
  const redirectUri = params.get('redirectUri')
  const state = params.get('state')
  if (!redirectUri || !state) {
    return
  }
  if (!isLoopbackHttpUrl(redirectUri)) {
    return
  }
  const handoff: DesktopHandoff = { redirectUri, state }
  sessionStorage.setItem(PENDING_HANDOFF_KEY, JSON.stringify(handoff))

  // Strip the params so a browser refresh doesn't re-capture them.
  const url = new URL(window.location.href)
  url.searchParams.delete('redirectUri')
  url.searchParams.delete('state')
  window.history.replaceState({}, '', url.toString())
}

/** The captured handoff, if the browser arrived from the plugin. */
export function readDesktopHandoff(): DesktopHandoff | null {
  try {
    const raw = sessionStorage.getItem(PENDING_HANDOFF_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as DesktopHandoff
    if (
      !parsed ||
      typeof parsed.redirectUri !== 'string' ||
      typeof parsed.state !== 'string' ||
      !isLoopbackHttpUrl(parsed.redirectUri)
    ) {
      clearDesktopHandoff()
      return null
    }
    return parsed
  } catch {
    return null
  }
}

/** Drop the handoff (after use, on failure, or on validation error). */
export function clearDesktopHandoff(): void {
  sessionStorage.removeItem(PENDING_HANDOFF_KEY)
}

/**
 * Deliver the handoff: attach the code (+ echoed state) to the plugin's
 * loopback redirect. Returns false when no handoff is pending (the
 * caller proceeds with the normal post-login navigation).
 */
export function redirectUriForHandoff(handoff: DesktopHandoff, code: string): string {
  const url = new URL(handoff.redirectUri)
  url.searchParams.set('code', code)
  url.searchParams.set('state', handoff.state)
  return url.toString()
}