import { createFileRoute } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import {
  authProvidersApi,
  type AuthProvider,
  type CreateAuthProviderRequest,
  type UpdateAuthProviderRequest,
} from '@/lib/api'
import {
  buildInitialMetadataForm,
  buildMetadataPayload,
  getProviderMetadataDefaults,
  mergeProviderMetadataWithDefaults,
  type ProviderType,
  type ProviderMetadataForm,
  validateProviderForm,
} from '@/lib/auth-provider-metadata'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Pencil, Power, ShieldCheck } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/auth-providers')({
  component: AuthProvidersPage,
})

function AuthProvidersPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editProvider, setEditProvider] = useState<AuthProvider | null>(null)

  const { data: providers, isLoading, error } = useQuery({
    queryKey: ['auth-providers'],
    queryFn: authProvidersApi.list,
  })

  const createMutation = useMutation({
    mutationFn: authProvidersApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['auth-providers'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ code, data }: { code: string; data: UpdateAuthProviderRequest }) =>
      authProvidersApi.update(code, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['auth-providers'] })
      setEditProvider(null)
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({ provider }: { provider: AuthProvider }) =>
      provider.isEnabled
        ? authProvidersApi.disable(provider.code)
        : authProvidersApi.enable(provider.code),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['auth-providers'] })
    },
  })

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading authentication providers...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load authentication providers</div>
      </div>
    )
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Authentication Providers</h1>
          <p className="text-muted-foreground">Manage LOCAL and external login providers</p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              Add Provider
            </Button>
          </DialogTrigger>
          <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
            <CreateProviderForm
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Configured Providers</CardTitle>
          <CardDescription>{providers?.length || 0} provider(s)</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-[150px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {providers?.map((provider) => (
                <TableRow key={provider.code}>
                  <TableCell className="font-medium">{provider.code}</TableCell>
                  <TableCell>
                    {provider.name}
                    {provider.isSystem && (
                      <Badge variant="secondary" className="ml-2">System</Badge>
                    )}
                  </TableCell>
                  <TableCell>{provider.providerType}</TableCell>
                  <TableCell>
                    <Badge variant={provider.isEnabled ? 'default' : 'secondary'}>
                      {provider.isEnabled ? 'Enabled' : 'Disabled'}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditProvider(provider)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled={toggleMutation.isPending}
                        onClick={() => toggleMutation.mutate({ provider })}
                      >
                        <Power className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5" />
            Security Guardrails
          </CardTitle>
          <CardDescription>
            LOCAL can only be disabled when policy allows, and bootstrap admin stays permanently LOCAL.
          </CardDescription>
        </CardHeader>
      </Card>

      <Dialog open={!!editProvider} onOpenChange={(open) => !open && setEditProvider(null)}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
          {editProvider && (
            <EditProviderForm
              provider={editProvider}
              onSubmit={(data) => updateMutation.mutate({ code: editProvider.code, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface CreateProviderFormProps {
  onSubmit: (data: CreateAuthProviderRequest) => void
  isLoading: boolean
  error?: string
}

function CreateProviderForm({ onSubmit, isLoading, error }: CreateProviderFormProps) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [providerType, setProviderType] = useState<ProviderType>('OIDC')
  const [metadata, setMetadata] = useState<ProviderMetadataForm>(buildInitialMetadataForm())
  const [validationErrors, setValidationErrors] = useState<string[]>([])

  useEffect(() => {
    setMetadata((prev: ProviderMetadataForm) => mergeProviderMetadataWithDefaults(providerType, prev))

    if (!name.trim()) {
      if (providerType === 'GOOGLE') {
        setName('Google')
      } else if (providerType === 'GITHUB') {
        setName('GitHub')
      }
    }

    if (!code.trim()) {
      if (providerType === 'GOOGLE') {
        setCode('GOOGLE')
      } else if (providerType === 'GITHUB') {
        setCode('GITHUB')
      }
    }
  }, [providerType, code, name])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()

    const normalizedCode = code.trim().toUpperCase()
    const normalizedName = name.trim()
    const errors = validateProviderForm({
      code: normalizedCode,
      name: normalizedName,
      providerType,
      metadata,
    })

    if (errors.length > 0) {
      setValidationErrors(errors)
      return
    }

    setValidationErrors([])
    onSubmit({
      code: normalizedCode,
      name: normalizedName,
      providerType,
      isEnabled: false,
      metadata: buildMetadataPayload(metadata),
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Add Authentication Provider</DialogTitle>
        <DialogDescription>Create a new external login provider</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>}
        {validationErrors.length > 0 && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md space-y-1">
            {validationErrors.map((validationError) => (
              <div key={validationError}>{validationError}</div>
            ))}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="provider-code">Code</Label>
          <Input
            id="provider-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="OIDC_CORP"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="provider-name">Name</Label>
          <Input
            id="provider-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Corporate OIDC"
            required
          />
        </div>
        <div className="space-y-2">
          <Label>Type</Label>
          <Select value={providerType} onValueChange={(value) => setProviderType(value as ProviderType)}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="OIDC">OIDC</SelectItem>
              <SelectItem value="GITHUB">GitHub</SelectItem>
              <SelectItem value="GOOGLE">Google</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <ProviderMetadataFields
          providerType={providerType}
          metadata={metadata}
          onChange={setMetadata}
          onApplyDefaults={() => setMetadata((prev: ProviderMetadataForm) => mergeProviderMetadataWithDefaults(providerType, prev))}
        />
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>{isLoading ? 'Creating...' : 'Create Provider'}</Button>
      </DialogFooter>
    </form>
  )
}

interface EditProviderFormProps {
  provider: AuthProvider
  onSubmit: (data: UpdateAuthProviderRequest) => void
  isLoading: boolean
  error?: string
}

function EditProviderForm({ provider, onSubmit, isLoading, error }: EditProviderFormProps) {
  const [name, setName] = useState(provider.name)
  const [isEnabled, setIsEnabled] = useState(provider.isEnabled)
  const [metadata, setMetadata] = useState<ProviderMetadataForm>(buildInitialMetadataForm(provider))
  const [validationErrors, setValidationErrors] = useState<string[]>([])
  const providerType = provider.providerType
  const hasConfiguredSecret = provider.metadata?.clientSecretConfigured === true

  useEffect(() => {
    setMetadata((prev: ProviderMetadataForm) => mergeProviderMetadataWithDefaults(providerType, prev))
  }, [providerType])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const errors = validateProviderForm({
      code: provider.code,
      name,
      providerType: provider.providerType,
      metadata,
    })

    if (errors.length > 0) {
      setValidationErrors(errors)
      return
    }

    setValidationErrors([])
    onSubmit({
      name: name.trim(),
      isEnabled,
      metadata: buildMetadataPayload(metadata),
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Provider</DialogTitle>
        <DialogDescription>Update provider configuration</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>}
        {validationErrors.length > 0 && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md space-y-1">
            {validationErrors.map((validationError) => (
              <div key={validationError}>{validationError}</div>
            ))}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="edit-provider-code">Code</Label>
          <Input id="edit-provider-code" value={provider.code} disabled />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-provider-type">Type</Label>
          <Input id="edit-provider-type" value={providerType} disabled />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-provider-name">Name</Label>
          <Input
            id="edit-provider-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <ProviderMetadataFields
          providerType={providerType}
          metadata={metadata}
          onChange={setMetadata}
          onApplyDefaults={() => setMetadata((prev: ProviderMetadataForm) => mergeProviderMetadataWithDefaults(providerType, prev))}
        />
        {hasConfiguredSecret && (
          <p className="text-xs text-muted-foreground">A client secret is already configured for this provider.</p>
        )}
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="edit-provider-enabled"
            checked={isEnabled}
            onChange={(e) => setIsEnabled(e.target.checked)}
            className="h-4 w-4"
          />
          <Label htmlFor="edit-provider-enabled">Enabled</Label>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>{isLoading ? 'Saving...' : 'Save Changes'}</Button>
      </DialogFooter>
    </form>
  )
}

interface ProviderMetadataFieldsProps {
  providerType: ProviderType
  metadata: ProviderMetadataForm
  onChange: (metadata: ProviderMetadataForm) => void
  onApplyDefaults: () => void
}

function ProviderMetadataFields({
  providerType,
  metadata,
  onChange,
  onApplyDefaults,
}: ProviderMetadataFieldsProps) {
  const defaults = useMemo(() => getProviderMetadataDefaults(providerType), [providerType])
  const callbackUrl = typeof window !== 'undefined' ? `${window.location.origin}/login` : '/login'
  const showLocalMessage = providerType === 'LOCAL'
  const showOidcFields = providerType === 'OIDC'

  if (showLocalMessage) {
    return (
      <div className="space-y-2 border rounded-md p-4 text-sm text-muted-foreground">
        <p>LOCAL provider does not require external identity provider configuration.</p>
      </div>
    )
  }

  return (
    <div className="space-y-4 border rounded-md p-4">
      <ProviderSetupGuide providerType={providerType} callbackUrl={callbackUrl} />

      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>
          {providerType === 'OIDC'
            ? 'Configure your identity provider endpoints and client settings.'
            : `Default ${providerType} endpoints are prefilled. Review before enabling.`}
        </span>
        <Button type="button" variant="outline" size="sm" onClick={onApplyDefaults}>
          Apply Defaults
        </Button>
      </div>

      <div className="space-y-2">
        <Label htmlFor="provider-authorization-url">Authorization URL</Label>
        <Input
          id="provider-authorization-url"
          value={metadata.authorizationUrl}
          onChange={(e) => onChange({ ...metadata, authorizationUrl: e.target.value })}
          placeholder={defaults.authorizationUrl || 'https://idp.example.com/oauth2/v2/auth'}
          required
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="provider-client-id">Client ID</Label>
        <Input
          id="provider-client-id"
          value={metadata.clientId}
          onChange={(e) => onChange({ ...metadata, clientId: e.target.value })}
          placeholder="myrmec-control-ui"
          required
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="provider-client-secret">Client Secret (optional)</Label>
        <Input
          id="provider-client-secret"
          type="password"
          value={metadata.clientSecret}
          onChange={(e) => onChange({ ...metadata, clientSecret: e.target.value })}
          placeholder="Enter client secret"
        />
        <p className="text-xs text-muted-foreground">
          Stored encrypted in DB. Secret value is never returned by API after save.
        </p>
      </div>

      <div className="space-y-2">
        <Label htmlFor="provider-token-url">Token URL (optional)</Label>
        <Input
          id="provider-token-url"
          value={metadata.tokenUrl}
          onChange={(e) => onChange({ ...metadata, tokenUrl: e.target.value })}
          placeholder={defaults.tokenUrl || 'https://idp.example.com/oauth2/token'}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="provider-user-info-url">User Info URL (optional)</Label>
        <Input
          id="provider-user-info-url"
          value={metadata.userInfoUrl}
          onChange={(e) => onChange({ ...metadata, userInfoUrl: e.target.value })}
          placeholder={defaults.userInfoUrl || 'https://idp.example.com/userinfo'}
        />
      </div>

      {showOidcFields && (
        <>
          <div className="space-y-2">
            <Label htmlFor="provider-issuer">Issuer (optional)</Label>
            <Input
              id="provider-issuer"
              value={metadata.issuer}
              onChange={(e) => onChange({ ...metadata, issuer: e.target.value })}
              placeholder={defaults.issuer || 'https://idp.example.com'}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="provider-audience">Audience (optional)</Label>
            <Input
              id="provider-audience"
              value={metadata.audience}
              onChange={(e) => onChange({ ...metadata, audience: e.target.value })}
              placeholder={defaults.audience || 'api://myrmec-control-engine'}
            />
          </div>
        </>
      )}

      <div className="space-y-2">
        <Label htmlFor="provider-scopes">Scopes (space-separated, optional)</Label>
        <Textarea
          id="provider-scopes"
          value={metadata.scopes}
          onChange={(e) => onChange({ ...metadata, scopes: e.target.value })}
          placeholder={defaults.scopes || 'openid profile email'}
          className="min-h-[70px]"
        />
      </div>
    </div>
  )
}

interface ProviderSetupGuideProps {
  providerType: ProviderType
  callbackUrl: string
}

function ProviderSetupGuide({ providerType, callbackUrl }: ProviderSetupGuideProps) {
  if (providerType === 'LOCAL') {
    return null
  }

  if (providerType === 'GITHUB') {
    return (
      <div className="rounded-md border bg-muted/30 p-3 text-xs space-y-2">
        <p className="font-medium">GitHub setup guide</p>
        <p>1. Create an OAuth App in GitHub Developer Settings.</p>
        <p>2. Set Authorization callback URL to: <span className="font-mono">{callbackUrl}</span></p>
        <p>3. Copy Client ID here and store Client Secret in your secret manager.</p>
        <p>4. Keep scopes minimal: <span className="font-mono">read:user user:email</span>.</p>
      </div>
    )
  }

  if (providerType === 'GOOGLE') {
    return (
      <div className="rounded-md border bg-muted/30 p-3 text-xs space-y-2">
        <p className="font-medium">Google setup guide</p>
        <p>1. Create OAuth Client (Web application) in Google Cloud Console.</p>
        <p>2. Add Authorized redirect URI: <span className="font-mono">{callbackUrl}</span></p>
        <p>3. Configure OAuth consent screen for your organization/users.</p>
        <p>4. Use scopes: <span className="font-mono">openid profile email</span>.</p>
      </div>
    )
  }

  return (
    <div className="rounded-md border bg-muted/30 p-3 text-xs space-y-2">
      <p className="font-medium">OIDC setup guide</p>
      <p>1. Register Myrmec as a client application in your IdP.</p>
      <p>2. Configure redirect URI: <span className="font-mono">{callbackUrl}</span></p>
      <p>3. Provide Authorization URL and Client ID at minimum.</p>
      <p>4. Add Token/UserInfo URLs for code exchange and profile lookup.</p>
    </div>
  )
}
