import type { AuthProvider } from './api'

export type ProviderType = 'LOCAL' | 'OIDC' | 'GITHUB' | 'GOOGLE'

export interface ProviderMetadataForm {
  authorizationUrl: string
  tokenUrl: string
  userInfoUrl: string
  issuer: string
  clientId: string
  clientSecret: string
  audience: string
  scopes: string
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function getProviderMetadataDefaults(providerType: ProviderType): ProviderMetadataForm {
  switch (providerType) {
    case 'GOOGLE':
      return {
        authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        tokenUrl: 'https://oauth2.googleapis.com/token',
        userInfoUrl: 'https://openidconnect.googleapis.com/v1/userinfo',
        issuer: 'https://accounts.google.com',
        clientId: '',
        clientSecret: '',
        audience: '',
        scopes: 'openid profile email',
      }
    case 'GITHUB':
      return {
        authorizationUrl: 'https://github.com/login/oauth/authorize',
        tokenUrl: 'https://github.com/login/oauth/access_token',
        userInfoUrl: 'https://api.github.com/user',
        issuer: 'https://github.com',
        clientId: '',
        clientSecret: '',
        audience: '',
        scopes: 'read:user user:email',
      }
    case 'OIDC':
      return {
        authorizationUrl: '',
        tokenUrl: '',
        userInfoUrl: '',
        issuer: '',
        clientId: '',
        clientSecret: '',
        audience: '',
        scopes: 'openid profile email',
      }
    case 'LOCAL':
    default:
      return {
        authorizationUrl: '',
        tokenUrl: '',
        userInfoUrl: '',
        issuer: '',
        clientId: '',
        clientSecret: '',
        audience: '',
        scopes: '',
      }
  }
}

export function mergeProviderMetadataWithDefaults(
  providerType: ProviderType,
  current: ProviderMetadataForm
): ProviderMetadataForm {
  const defaults = getProviderMetadataDefaults(providerType)
  return {
    authorizationUrl: current.authorizationUrl.trim() || defaults.authorizationUrl,
    tokenUrl: current.tokenUrl.trim() || defaults.tokenUrl,
    userInfoUrl: current.userInfoUrl.trim() || defaults.userInfoUrl,
    issuer: current.issuer.trim() || defaults.issuer,
    clientId: current.clientId.trim() || defaults.clientId,
    clientSecret: current.clientSecret.trim() || defaults.clientSecret,
    audience: current.audience.trim() || defaults.audience,
    scopes: current.scopes.trim() || defaults.scopes,
  }
}

export function buildInitialMetadataForm(provider?: AuthProvider): ProviderMetadataForm {
  const metadata = (provider?.metadata ?? {}) as Record<string, unknown>

  const initial: ProviderMetadataForm = {
    authorizationUrl: readString(metadata.authorizationUrl),
    tokenUrl: readString(metadata.tokenUrl),
    userInfoUrl: readString(metadata.userInfoUrl),
    issuer: readString(metadata.issuer),
    clientId: readString(metadata.clientId),
    clientSecret: '',
    audience: readString(metadata.audience),
    scopes: Array.isArray(metadata.scopes)
      ? metadata.scopes.filter((value) => typeof value === 'string').join(' ')
      : readString(metadata.scopes),
  }

  if (!provider?.providerType) {
    return initial
  }

  return mergeProviderMetadataWithDefaults(provider.providerType, initial)
}

export function buildMetadataPayload(form: ProviderMetadataForm): Record<string, unknown> {
  const payload: Record<string, unknown> = {}
  const authorizationUrl = form.authorizationUrl.trim()
  const tokenUrl = form.tokenUrl.trim()
  const userInfoUrl = form.userInfoUrl.trim()
  const issuer = form.issuer.trim()
  const clientId = form.clientId.trim()
  const clientSecret = form.clientSecret.trim()
  const audience = form.audience.trim()
  const scopes = form.scopes.trim()

  if (authorizationUrl) {
    payload.authorizationUrl = authorizationUrl
  }
  if (tokenUrl) {
    payload.tokenUrl = tokenUrl
  }
  if (userInfoUrl) {
    payload.userInfoUrl = userInfoUrl
  }
  if (issuer) {
    payload.issuer = issuer
  }
  if (clientId) {
    payload.clientId = clientId
  }
  if (clientSecret) {
    payload.clientSecret = clientSecret
  }
  if (audience) {
    payload.audience = audience
  }
  if (scopes) {
    payload.scopes = scopes.split(/\s+/).filter(Boolean)
  }

  return payload
}

export interface ProviderFormValidationInput {
  code: string
  name: string
  providerType: ProviderType
  metadata: ProviderMetadataForm
}

function validateHttpsUrl(value: string, fieldName: string, errors: string[]) {
  if (!value) {
    return
  }
  try {
    const parsed = new URL(value)
    if (parsed.protocol !== 'https:') {
      errors.push(`${fieldName} must use HTTPS.`)
    }
  } catch {
    errors.push(`${fieldName} must be a valid URL.`)
  }
}

export function validateProviderForm(input: ProviderFormValidationInput): string[] {
  const errors: string[] = []

  if (!/^[A-Z][A-Z0-9_]{1,49}$/.test(input.code.trim())) {
    errors.push('Code must start with a letter and contain only A-Z, 0-9, and underscore.')
  }

  if (!input.name.trim()) {
    errors.push('Name is required.')
  }

  if (!['LOCAL', 'OIDC', 'GITHUB', 'GOOGLE'].includes(input.providerType)) {
    errors.push('Provider type must be LOCAL, OIDC, GITHUB, or GOOGLE.')
  }

  const authorizationUrl = input.metadata.authorizationUrl.trim()
  const clientId = input.metadata.clientId.trim()
  const tokenUrl = input.metadata.tokenUrl.trim()
  const userInfoUrl = input.metadata.userInfoUrl.trim()

  if (input.providerType !== 'LOCAL' && !authorizationUrl) {
    errors.push('Authorization URL is required for external providers.')
  }

  if (input.providerType !== 'LOCAL' && !clientId) {
    errors.push('Client ID is required for external providers.')
  }

  validateHttpsUrl(authorizationUrl, 'Authorization URL', errors)
  validateHttpsUrl(tokenUrl, 'Token URL', errors)
  validateHttpsUrl(userInfoUrl, 'User info URL', errors)

  return errors
}
