import {
  clearExternalCallbackParams,
  consumeExternalAuthProvider,
  readExternalCallbackParams,
  registerExternalAuthState,
} from './external-auth'

describe('external auth helpers', () => {
  beforeEach(() => {
    sessionStorage.clear()
    window.history.replaceState({}, '', 'http://localhost:3000/login')
  })

  it('registers and consumes provider by state exactly once', () => {
    registerExternalAuthState('state-1', 'oidc_corp')

    expect(consumeExternalAuthProvider('state-1')).toBe('OIDC_CORP')
    expect(consumeExternalAuthProvider('state-1')).toBeNull()
  })

  it('reads callback params from query string', () => {
    const params = readExternalCallbackParams('?state=s1&code=auth-code-123')
    expect(params.state).toBe('s1')
    expect(params.code).toBe('auth-code-123')
    expect(params.error).toBeNull()
  })

  it('clears callback params from browser URL', () => {
    window.history.replaceState(
      {},
      '',
      'http://localhost:3000/login?state=s1&code=auth-code-123&error=bad'
    )

    clearExternalCallbackParams()
    expect(window.location.search).toBe('')
  })
})
