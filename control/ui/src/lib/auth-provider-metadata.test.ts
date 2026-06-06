import {
  buildInitialMetadataForm,
  buildMetadataPayload,
  getProviderMetadataDefaults,
  mergeProviderMetadataWithDefaults,
  validateProviderForm,
} from './auth-provider-metadata'

describe('auth provider metadata', () => {
  it('builds metadata payload with normalized scopes', () => {
    const payload = buildMetadataPayload({
      authorizationUrl: 'https://idp.example.com/auth',
      tokenUrl: 'https://idp.example.com/token',
      userInfoUrl: 'https://idp.example.com/userinfo',
      issuer: 'https://idp.example.com',
      clientId: 'my-client',
      clientSecret: 'my-secret',
      audience: 'api://myrmec',
      scopes: 'openid profile email',
    })

    expect(payload).toEqual({
      authorizationUrl: 'https://idp.example.com/auth',
      tokenUrl: 'https://idp.example.com/token',
      userInfoUrl: 'https://idp.example.com/userinfo',
      issuer: 'https://idp.example.com',
      clientId: 'my-client',
      clientSecret: 'my-secret',
      audience: 'api://myrmec',
      scopes: ['openid', 'profile', 'email'],
    })
  })

  it('validates required and URL rules', () => {
    const errors = validateProviderForm({
      code: 'oidc-corp',
      name: '',
      providerType: 'OIDC',
      metadata: {
        authorizationUrl: 'http://invalid.example.com/auth',
        tokenUrl: '',
        userInfoUrl: '',
        issuer: '',
        clientId: '',
        clientSecret: '',
        audience: '',
        scopes: '',
      },
    })

    expect(errors).toContain(
      'Code must start with a letter and contain only A-Z, 0-9, and underscore.'
    )
    expect(errors).toContain('Name is required.')
    expect(errors).toContain('Authorization URL must use HTTPS.')
    expect(errors).toContain('Client ID is required for external providers.')
  })

  it('reads metadata defaults safely', () => {
    const form = buildInitialMetadataForm()
    expect(form.authorizationUrl).toBe('')
    expect(form.scopes).toBe('')
  })

  it('provides github defaults and merges blank values', () => {
    const defaults = getProviderMetadataDefaults('GITHUB')
    const merged = mergeProviderMetadataWithDefaults('GITHUB', {
      authorizationUrl: '',
      tokenUrl: '',
      userInfoUrl: '',
      issuer: '',
      clientId: '',
      clientSecret: '',
      audience: '',
      scopes: '',
    })

    expect(defaults.authorizationUrl).toContain('github.com/login/oauth/authorize')
    expect(merged.scopes).toContain('user:email')
  })
})
