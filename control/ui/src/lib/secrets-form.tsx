import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { CredentialType, SecretPayload } from '@/lib/api'

export const CREDENTIAL_TYPE_OPTIONS: { value: CredentialType; label: string; hint: string }[] = [
  { value: 'BEARER_TOKEN', label: 'Bearer Token', hint: 'Git PATs, simple API tokens' },
  { value: 'USERNAME_PASSWORD', label: 'Username + Password', hint: 'Basic auth, SMTP, legacy systems' },
  { value: 'API_KEY', label: 'API Key', hint: 'Key + optional header name (X-API-Key)' },
  { value: 'SECRET_KEY', label: 'Secret Key', hint: 'Single opaque secret string' },
  { value: 'OAUTH_CLIENT', label: 'OAuth Client', hint: 'client_id + client_secret pair' },
  { value: 'SSL_PRIVATE_KEY', label: 'SSL Private Key', hint: 'PEM key with optional certificate and passphrase' },
  { value: 'CUSTOM', label: 'Custom (JSON)', hint: 'Free-form structured secret' },
]

/** Empty payload of the given type, ready to be bound into a form. */
export function emptyPayloadFor(type: CredentialType): SecretPayload {
  switch (type) {
    case 'BEARER_TOKEN':
      return { type: 'BEARER_TOKEN', token: '' }
    case 'USERNAME_PASSWORD':
      return { type: 'USERNAME_PASSWORD', username: '', password: '' }
    case 'API_KEY':
      return { type: 'API_KEY', key: '', header: '' }
    case 'SECRET_KEY':
      return { type: 'SECRET_KEY', secret: '' }
    case 'OAUTH_CLIENT':
      return { type: 'OAUTH_CLIENT', clientId: '', clientSecret: '' }
    case 'SSL_PRIVATE_KEY':
      return { type: 'SSL_PRIVATE_KEY', privateKey: '', certificate: '', passphrase: '' }
    case 'CUSTOM':
      return { type: 'CUSTOM', data: {} }
  }
}

/** Quick predicate the knowledge-repo dropdown uses to filter to git-compatible secrets. */
export function isGitCompatible(type: CredentialType): boolean {
  return type === 'BEARER_TOKEN' || type === 'USERNAME_PASSWORD' || type === 'SSL_PRIVATE_KEY'
}

interface Props {
  payload: SecretPayload
  onChange: (next: SecretPayload) => void
  disabled?: boolean
}

/** Renders the per-credential-type input fields for a Secret form. */
export function SecretCredentialFields({ payload, onChange, disabled }: Props) {
  switch (payload.type) {
    case 'BEARER_TOKEN':
      return (
        <div className="space-y-2">
          <Label htmlFor="cred-token">Token *</Label>
          <Input
            id="cred-token"
            type="password"
            value={payload.token}
            onChange={(e) => onChange({ ...payload, token: e.target.value })}
            required
            disabled={disabled}
            autoComplete="new-password"
          />
        </div>
      )

    case 'USERNAME_PASSWORD':
      return (
        <>
          <div className="space-y-2">
            <Label htmlFor="cred-username">Username *</Label>
            <Input
              id="cred-username"
              value={payload.username}
              onChange={(e) => onChange({ ...payload, username: e.target.value })}
              required
              disabled={disabled}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cred-password">Password *</Label>
            <Input
              id="cred-password"
              type="password"
              value={payload.password}
              onChange={(e) => onChange({ ...payload, password: e.target.value })}
              required
              disabled={disabled}
              autoComplete="new-password"
            />
          </div>
        </>
      )

    case 'API_KEY':
      return (
        <>
          <div className="space-y-2">
            <Label htmlFor="cred-key">Key *</Label>
            <Input
              id="cred-key"
              type="password"
              value={payload.key}
              onChange={(e) => onChange({ ...payload, key: e.target.value })}
              required
              disabled={disabled}
              autoComplete="new-password"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cred-header">Header name (optional)</Label>
            <Input
              id="cred-header"
              value={payload.header ?? ''}
              onChange={(e) => onChange({ ...payload, header: e.target.value || null })}
              placeholder="X-API-Key"
              disabled={disabled}
            />
          </div>
        </>
      )

    case 'SECRET_KEY':
      return (
        <div className="space-y-2">
          <Label htmlFor="cred-secret">Secret *</Label>
          <Input
            id="cred-secret"
            type="password"
            value={payload.secret}
            onChange={(e) => onChange({ ...payload, secret: e.target.value })}
            required
            disabled={disabled}
            autoComplete="new-password"
          />
        </div>
      )

    case 'OAUTH_CLIENT':
      return (
        <>
          <div className="space-y-2">
            <Label htmlFor="cred-client-id">Client ID *</Label>
            <Input
              id="cred-client-id"
              value={payload.clientId}
              onChange={(e) => onChange({ ...payload, clientId: e.target.value })}
              required
              disabled={disabled}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cred-client-secret">Client Secret *</Label>
            <Input
              id="cred-client-secret"
              type="password"
              value={payload.clientSecret}
              onChange={(e) => onChange({ ...payload, clientSecret: e.target.value })}
              required
              disabled={disabled}
              autoComplete="new-password"
            />
          </div>
        </>
      )

    case 'SSL_PRIVATE_KEY':
      return (
        <>
          <div className="space-y-2">
            <Label htmlFor="cred-private-key">Private Key (PEM) *</Label>
            <Textarea
              id="cred-private-key"
              value={payload.privateKey}
              onChange={(e) => onChange({ ...payload, privateKey: e.target.value })}
              required
              disabled={disabled}
              rows={6}
              className="font-mono text-xs"
              placeholder="-----BEGIN RSA PRIVATE KEY-----"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cred-certificate">Certificate (PEM, optional)</Label>
            <Textarea
              id="cred-certificate"
              value={payload.certificate ?? ''}
              onChange={(e) =>
                onChange({ ...payload, certificate: e.target.value || null })
              }
              disabled={disabled}
              rows={4}
              className="font-mono text-xs"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="cred-passphrase">Passphrase (optional)</Label>
            <Input
              id="cred-passphrase"
              type="password"
              value={payload.passphrase ?? ''}
              onChange={(e) =>
                onChange({ ...payload, passphrase: e.target.value || null })
              }
              disabled={disabled}
              autoComplete="new-password"
            />
          </div>
        </>
      )

    case 'CUSTOM':
      return (
        <div className="space-y-2">
          <Label htmlFor="cred-custom">Data (JSON) *</Label>
          <Textarea
            id="cred-custom"
            value={JSON.stringify(payload.data, null, 2)}
            onChange={(e) => {
              try {
                const parsed = JSON.parse(e.target.value)
                if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                  onChange({ type: 'CUSTOM', data: parsed as Record<string, unknown> })
                }
              } catch {
                // Ignore parse errors mid-edit; form-level validation should catch them on submit.
              }
            }}
            rows={6}
            className="font-mono text-xs"
            disabled={disabled}
            placeholder='{"key": "value"}'
          />
          <p className="text-xs text-muted-foreground">
            Must be a JSON object. Field names are free-form.
          </p>
        </div>
      )
  }
}
