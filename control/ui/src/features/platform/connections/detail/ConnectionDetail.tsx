// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect } from 'react'
import {
  connectionConfigApi,
  globalSecretsApi,
  type ConnectionConfigVersion,
  type ConnectionType,
  type Secret,
  type TestConnectionResult,
  ApiRequestError,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Link2, Plus, Save, Send, Trash2, Power, PowerOff, Archive, PlugZap } from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import { TYPE_LABELS, STATUS_COLORS, ALLOWED_SECRET_TYPES, TYPE_CONFIG_FIELDS, type ConfigField } from '../shared/constants'

export function ConnectionDetail({ configId, initialEdit = false }: { configId: string; initialEdit?: boolean }) {
  const queryClient = useQueryClient()

  const { data: config, isLoading } = useQuery({
    queryKey: ['connection-config', configId],
    queryFn: () => connectionConfigApi.get(configId),
  })

  const { data: publishedVersion } = useQuery({
    queryKey: ['connection-config-published', configId],
    queryFn: () => connectionConfigApi.getPublishedVersion(configId),
    enabled: !!config?.currentVersionId,
  })

  const { data: draftVersion } = useQuery({
    queryKey: ['connection-config-draft', configId],
    queryFn: () => connectionConfigApi.getDraftVersion(configId),
    retry: false,
  })

  // Sync Zone 1 edit state when config loads
  const [zone1Editing, setZone1Editing] = useState(initialEdit)
  const [publishError, setPublishError] = useState<string | null>(null)
  const [zone1Name, setZone1Name] = useState('')
  const [zone1Desc, setZone1Desc] = useState('')
  const [zone1SecretId, setZone1SecretId] = useState<string>('')

  // Fetch global secrets for credential selector and display
  const { data: secrets } = useQuery({
    queryKey: ['global-secrets'],
    queryFn: globalSecretsApi.list,
  })

  // Initialize zone1 fields when config data arrives
  if (config && !zone1Name && config.name) {
    setZone1Name(config.name)
    setZone1Desc(config.description ?? '')
    setZone1SecretId(config.credentialSecretId ?? '')
  }

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['connection-config', configId] })
    queryClient.invalidateQueries({ queryKey: ['connection-config-published', configId] })
    queryClient.invalidateQueries({ queryKey: ['connection-config-draft', configId] })
    queryClient.invalidateQueries({ queryKey: ['connection-configs'] })
  }

  const createDraftMutation = useMutation({
    mutationFn: () => connectionConfigApi.createDraft(configId),
    onSuccess: invalidate,
  })

  const updateConfigMutation = useMutation({
    mutationFn: (data: { name?: string; description?: string; credentialSecretId?: string | null }) =>
      connectionConfigApi.update(configId, data),
    onSuccess: () => {
      setZone1Editing(false)
      invalidate()
    },
  })

  const publishMutation = useMutation({
    mutationFn: () => connectionConfigApi.publish(configId),
    onSuccess: () => {
      // Exit Zone 1 edit mode after publish (Pattern 1: return to View mode)
      setZone1Editing(false)
      // Remove the draft query from cache entirely so the UI switches
      // to the Published Version card without waiting for refetch.
      queryClient.removeQueries({ queryKey: ['connection-config-draft', configId] })
      setPublishError(null)
      invalidate()
    },
    onError: (error) => {
      console.error('Publish failed:', error)
      if (error instanceof ApiRequestError && error.error?.details) {
        const details = error.error.details as Array<{ message?: string }>
        const detail = details.find((d) => d.message)
        setPublishError(detail?.message ?? 'Connection test failed')
      } else if (error instanceof ApiRequestError && error.error?.message) {
        setPublishError(error.error.message)
      } else {
        setPublishError('Connection test failed')
      }
    },
  })

  const discardMutation = useMutation({
    mutationFn: () => connectionConfigApi.discardDraft(configId),
    onSuccess: () => {
      // Remove the draft query from cache entirely so the UI switches
      // to the "No Version Yet" state without waiting for refetch.
      queryClient.removeQueries({ queryKey: ['connection-config-draft', configId] })
      invalidate()
    },
  })

  const disableMutation = useMutation({
    mutationFn: () => connectionConfigApi.disable(configId),
    onSuccess: invalidate,
  })

  const reenableMutation = useMutation({
    mutationFn: () => connectionConfigApi.reenable(configId),
    onSuccess: invalidate,
  })

  const archiveMutation = useMutation({
    mutationFn: () => connectionConfigApi.archive(configId),
    onSuccess: invalidate,
  })

  if (isLoading || !config) {
    return <div className="p-8 text-muted-foreground">Loading connection config…</div>
  }

  const hasDraft = !!draftVersion
  const hasPublished = !!publishedVersion

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/connections" className="hover:underline">Connection Configs</Link>
        <span>/</span>
        <span className="text-foreground font-medium">{config.name}</span>
      </div>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <Link2 className="h-6 w-6 text-muted-foreground" />
          <div>
            <h1 className="text-2xl font-bold">{config.name}</h1>
            {config.description && <p className="text-muted-foreground">{config.description}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{TYPE_LABELS[config.type] || config.type}</Badge>
          <div className="flex items-center gap-1">
            <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[config.status] || 'bg-gray-400'}`} />
            <span className="text-sm">{config.status}</span>
          </div>
        </div>
      </div>

      {/* Tabs (Pattern 2 §Rule 1) */}
      <div className="flex gap-1 border-b mb-6" role="tablist">
        <button role="tab" aria-selected="true" className="px-4 py-2 text-sm font-medium border-b-2 border-primary text-primary">Details</button>
        <button role="tab" aria-selected="false" className="px-4 py-2 text-sm font-medium border-b-2 border-transparent text-muted-foreground hover:text-foreground">Version History</button>
        <button role="tab" aria-selected="false" className="px-4 py-2 text-sm font-medium border-b-2 border-transparent text-muted-foreground hover:text-foreground">Audit Log</button>
      </div>

      {/* Zone 1: Identity (Pattern 1: View/Edit Mode) */}
      <Card className="mb-6">
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">Identity & Metadata</CardTitle>
            <Button variant="outline" size="sm" onClick={() => setZone1Editing(!zone1Editing)}>
              {zone1Editing ? 'Cancel' : 'Edit'}
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {zone1Editing ? (
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="zone1-name">Name</Label>
                <Input id="zone1-name" value={zone1Name} onChange={(e) => setZone1Name(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="zone1-desc">Description</Label>
                <Input id="zone1-desc" value={zone1Desc} onChange={(e) => setZone1Desc(e.target.value)} placeholder="Brief description" />
              </div>
              <div className="space-y-2">
                <Label htmlFor="zone1-secret">Credential Secret</Label>
                <Select
                  value={zone1SecretId || '__none__'}
                  onValueChange={(v) => setZone1SecretId(v === '__none__' ? '' : v)}
                >
                  <SelectTrigger id="zone1-secret">
                    <SelectValue placeholder="Select a credential secret" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">— None —</SelectItem>
                    {(secrets ?? [])
                      .filter((s) => ALLOWED_SECRET_TYPES[config.type].includes(s.type))
                      .map((s) => (
                        <SelectItem key={s.id} value={s.id}>
                          {s.name} ({s.type})
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={() => {
                    updateConfigMutation.mutate({
                      name: zone1Name,
                      description: zone1Desc,
                      credentialSecretId: zone1SecretId || null,
                    })
                  }}
                  disabled={updateConfigMutation.isPending}
                >
                  {updateConfigMutation.isPending ? 'Saving…' : 'Save'}
                </Button>
                <Button size="sm" variant="outline" onClick={() => {
                  setZone1Name(config.name)
                  setZone1Desc(config.description ?? '')
                  setZone1SecretId(config.credentialSecretId ?? '')
                  setZone1Editing(false)
                }}>Cancel</Button>
              </div>
            </div>
          ) : (
            <dl className="grid grid-cols-2 gap-4 text-sm">
              <div><dt className="text-muted-foreground">Name</dt><dd>{config.name}</dd></div>
              <div><dt className="text-muted-foreground">Type</dt><dd>{TYPE_LABELS[config.type] || config.type}</dd></div>
              <div><dt className="text-muted-foreground">Scope</dt><dd>{config.scope}</dd></div>
              <div><dt className="text-muted-foreground">Description</dt><dd>{config.description || '—'}</dd></div>
              <div><dt className="text-muted-foreground">Credential Secret</dt><dd>{config.credentialSecretId ? secrets?.find((s) => s.id === config.credentialSecretId)?.name ?? config.credentialSecretId : '—'}</dd></div>
              <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(config.createdAt).toLocaleString()}</dd></div>
              {config.publishedAt && (
                <div><dt className="text-muted-foreground">Published</dt><dd>{new Date(config.publishedAt).toLocaleString()}</dd></div>
              )}
            </dl>
          )}
        </CardContent>
      </Card>

      {/* Zone 2: Version */}
      {hasPublished && !hasDraft && (
        <Card className="mb-6">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base">Published Version (v{publishedVersion!.versionNumber})</CardTitle>
                <CardDescription>{publishedVersion!.publishedAt ? new Date(publishedVersion!.publishedAt).toLocaleString() : '—'}</CardDescription>
              </div>
              <Badge>Published</Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><dt className="text-muted-foreground">URL</dt><dd>{publishedVersion!.url || '—'}</dd></div>
            </div>
            {publishedVersion!.config && (
              <div>
                <Label className="mb-2 block">Config</Label>
                <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                  {JSON.stringify(publishedVersion!.config, null, 2)}
                </pre>
              </div>
            )}
            {!zone1Editing && (
              <Button onClick={() => createDraftMutation.mutate()} disabled={createDraftMutation.isPending}>
                <Plus className="h-4 w-4 mr-2" /> New Draft Version
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {hasDraft && (
        <DraftSection
          configId={configId}
          connectionType={config.type}
          version={draftVersion!}
          onPublish={() => { setPublishError(null); publishMutation.mutate() }}
          onDiscard={async () => {
            const confirmed = await dialogService.showConfirmDialog({
              title: 'Discard Draft',
              message: 'Discard this draft? Any unsaved changes will be lost.',
              severity: 'warning',
              type: 'warning',
              confirmLabel: 'Discard',
              cancelLabel: 'Cancel',
            })
            if (confirmed) discardMutation.mutate()
          }}
          publishing={publishMutation.isPending}
          discarding={discardMutation.isPending}
          publishError={publishError}
        />
      )}

      {!hasPublished && !hasDraft && (
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="text-base">No Version Yet</CardTitle>
            <CardDescription>Create a draft to configure and publish this connection.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => createDraftMutation.mutate()} disabled={createDraftMutation.isPending}>
              <Plus className="h-4 w-4 mr-2" /> Create First Draft
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Actions */}
      <div className="flex items-center gap-2 mt-6">
        {config.status === 'ACTIVE' && (
          <Button variant="outline" onClick={() => disableMutation.mutate()} disabled={disableMutation.isPending}>
            <PowerOff className="h-4 w-4 mr-2" /> Disable
          </Button>
        )}
        {config.status === 'DISABLED' && (
          <Button variant="outline" onClick={() => reenableMutation.mutate()} disabled={reenableMutation.isPending}>
            <Power className="h-4 w-4 mr-2" /> Re-enable
          </Button>
        )}
        {config.status !== 'ARCHIVED' && (
          <Button variant="ghost" className="text-destructive" onClick={async () => {
            const confirmed = await dialogService.showConfirmDialog({
              title: 'Archive Connection Config',
              message: `Archive "${config.name}"? Archived configs are hidden from the list but can be restored.`,
              severity: 'warning',
              type: 'warning',
              confirmLabel: 'Archive',
              cancelLabel: 'Cancel',
            })
            if (confirmed) archiveMutation.mutate()
          }} disabled={archiveMutation.isPending}>
            <Archive className="h-4 w-4 mr-2" /> Archive
          </Button>
        )}
      </div>
    </ContentAreaLayout>
  )
}

function DraftSection({
  configId,
  connectionType,
  version,
  onPublish,
  onDiscard,
  publishing,
  discarding,
  publishError,
}: {
  configId: string
  connectionType: ConnectionType
  version: ConnectionConfigVersion
  onPublish: () => void
  onDiscard: () => void
  publishing: boolean
  discarding: boolean
  publishError: string | null
}) {
  const queryClient = useQueryClient()
  const [url, setUrl] = useState(version.url ?? '')

  // Sync url when version changes (e.g. after save+refetch)
  useEffect(() => {
    setUrl(version.url ?? '')
  }, [version.url])
  const [configValues, setConfigValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    const config = version.config ?? {}
    for (const field of TYPE_CONFIG_FIELDS[connectionType] ?? []) {
      const v = config[field.key]
      initial[field.key] = v != null ? String(typeof v === 'object' ? JSON.stringify(v) : v) : ''
    }
    return initial
  })

  // Sync configValues when version changes (e.g. after save+refetch)
  useEffect(() => {
    const config = version.config ?? {}
    setConfigValues(() => {
      const updated: Record<string, string> = {}
      for (const field of TYPE_CONFIG_FIELDS[connectionType] ?? []) {
        const v = config[field.key]
        updated[field.key] = v != null ? String(typeof v === 'object' ? JSON.stringify(v) : v) : ''
      }
      return updated
    })
  }, [version.config, connectionType])

  const fields = TYPE_CONFIG_FIELDS[connectionType] ?? []

  const [testResult, setTestResult] = useState<TestConnectionResult | null>(null)

  const testMutation = useMutation({
    mutationFn: () => {
      // Stateless test: send current URL and config from the form (no save required)
      const configObj: Record<string, unknown> = {}
      for (const field of fields) {
        const v = configValues[field.key]
        if (v != null && v !== '') {
          if (field.type === 'number') {
            configObj[field.key] = Number(v)
          } else if (field.key === 'headers') {
            try {
              configObj[field.key] = JSON.parse(v as string)
            } catch {
              configObj[field.key] = v
            }
          } else {
            configObj[field.key] = v
          }
        }
      }
      return connectionConfigApi.testConnection(configId, { url, config: configObj })
    },
    onSuccess: (result) => {
      setTestResult(result)
      // Invalidate draft to pick up the updated testStatus
      queryClient.invalidateQueries({ queryKey: ['connection-config-draft', configId] })
    },
    onError: (error) => {
      setTestResult({
        status: 'FAILED',
        latencyMs: 0,
        endpoint: url,
        authenticated: null,
        error: error instanceof Error ? error.message : String(error),
      })
      queryClient.invalidateQueries({ queryKey: ['connection-config-draft', configId] })
    },
  })

  // BR-CC-14: HTTP connections need test_endpoint before [Test Connection] is enabled
  const testEndpointValue = configValues['testEndpoint'] ?? ''
  const testButtonDisabled =
    connectionType === 'HTTP' && (!testEndpointValue || testEndpointValue.trim() === '')

  // BR-CC-15: Publish gate is enforced server-side (connectivity check at publish time).
  // The UI only disables Publish when required fields (URL) are missing.
  const canPublish = url.trim() !== ''

  const saveMutation = useMutation({
    mutationFn: () => {
      // Build config object from type-specific fields
      const configObj: Record<string, unknown> = {}
      for (const field of fields) {
        const v = configValues[field.key]
        if (v != null && v !== '') {
          if (field.type === 'number') {
            configObj[field.key] = Number(v)
          } else if (field.key === 'headers') {
            // Headers is a JSON string — parse it
            try {
              configObj[field.key] = JSON.parse(v)
            } catch {
              configObj[field.key] = v
            }
          } else {
            configObj[field.key] = v
          }
        }
      }
      return connectionConfigApi.updateDraft(configId, {
        url: url || undefined,
        config: Object.keys(configObj).length > 0 ? configObj : undefined,
      })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-config-draft', configId] }),
  })

  const dirty =
    url !== (version.url ?? '') ||
    fields.some((f) => {
      const current = configValues[f.key]
      const saved = version.config?.[f.key]
      if (saved == null) return current !== ''
      if (typeof saved === 'object') return current !== JSON.stringify(saved)
      return current !== String(saved)
    })

  return (
    <Card className="mb-6 border-primary/30">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Draft (v{version.versionNumber})</CardTitle>
            <CardDescription>Edit and publish this draft</CardDescription>
          </div>
          <Badge variant="secondary">Draft</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="draft-url">URL</Label>
          <Input id="draft-url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://api.example.com" />
        </div>
        {/* Type-specific config fields (UC-018 §5) */}
        {fields.map((field) => (
          <div key={field.key} className="space-y-2">
            <Label htmlFor={`draft-config-${field.key}`}>{field.label}</Label>
            {field.type === 'select' ? (
              <Select
                value={configValues[field.key] || '__none__'}
                onValueChange={(v) => setConfigValues((prev) => ({ ...prev, [field.key]: v === '__none__' ? '' : v }))}
              >
                <SelectTrigger id={`draft-config-${field.key}`}>
                  <SelectValue placeholder={`Select ${field.label}`} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">— None —</SelectItem>
                  {(field.options ?? []).map((opt) => (
                    <SelectItem key={opt} value={opt}>{opt}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            ) : (
              <Input
                id={`draft-config-${field.key}`}
                value={configValues[field.key] ?? ''}
                onChange={(e) => setConfigValues((prev) => ({ ...prev, [field.key]: e.target.value }))}
                placeholder={field.placeholder}
                type={field.type === 'number' ? 'number' : 'text'}
              />
            )}
          </div>
        ))}
        {version.config && (
          <div>
            <Label className="mb-2 block">Config</Label>
            <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
              {JSON.stringify(version.config, null, 2)}
            </pre>
          </div>
        )}
        <div className="flex items-center gap-2 pt-2">
          <Button size="sm" onClick={() => saveMutation.mutate()} disabled={!dirty || saveMutation.isPending}>
            <Save className="h-4 w-4 mr-2" /> {saveMutation.isPending ? 'Saving…' : 'Save Draft'}
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={() => testMutation.mutate()}
            disabled={testButtonDisabled || testMutation.isPending}
            title={testButtonDisabled ? 'Enter a Test Endpoint path to test this connection.' : undefined}
          >
            <PlugZap className="h-4 w-4 mr-2" /> {testMutation.isPending ? 'Testing…' : 'Test Connection'}
          </Button>
          <Button
            size="sm"
            onClick={onPublish}
            disabled={publishing || !canPublish}
          >
            <Send className="h-4 w-4 mr-2" /> {publishing ? 'Publishing…' : 'Publish'}
          </Button>
          <Button size="sm" variant="ghost" className="text-destructive" onClick={onDiscard} disabled={discarding}>
            <Trash2 className="h-4 w-4 mr-2" /> {discarding ? 'Discarding…' : 'Discard'}
          </Button>
        </div>

        {/* Publish error display (BR-CC-15 server-side connectivity check) */}
        {publishError && (
          <div className="flex items-center gap-2 text-sm text-destructive pt-2" role="alert">
            <span>❌ {publishError}</span>
          </div>
        )}

        {/* Test status badge */}
        {version.testStatus && (
          <div className="flex items-center gap-2 text-sm">
            {version.testStatus === 'SUCCESS' ? (
              <Badge className="bg-green-500 text-white">Test: Connected</Badge>
            ) : (
              <Badge className="bg-red-500 text-white">Test: Failed</Badge>
            )}
            {version.lastTestAt && (
              <span className="text-muted-foreground">
                {new Date(version.lastTestAt).toLocaleString()}
              </span>
            )}
            {version.lastTestError && (
              <span className="text-destructive text-xs">{version.lastTestError}</span>
            )}
          </div>
        )}

        {/* Test result dialog */}
        {testResult && (
          <TestResultDialog result={testResult} onClose={() => setTestResult(null)} />
        )}
      </CardContent>
    </Card>
  )
}

function TestResultDialog({ result, onClose }: { result: TestConnectionResult; onClose: () => void }) {
  const isSuccess = result.status === 'SUCCESS'
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" role="dialog" aria-modal="true">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-base">Test Connection Result</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="flex items-center gap-2">
            <span className="font-medium">Status:</span>
            {isSuccess ? (
              <Badge className="bg-green-500 text-white">✅ Connected</Badge>
            ) : (
              <Badge className="bg-red-500 text-white">❌ Failed</Badge>
            )}
          </div>
          <div><span className="font-medium">Latency:</span> {result.latencyMs}ms</div>
          {result.endpoint && <div><span className="font-medium">Endpoint:</span> {result.endpoint}</div>}
          {result.authenticated != null && (
            <div><span className="font-medium">Authenticated:</span> {result.authenticated ? 'yes' : 'no'}</div>
          )}
          {result.error && (
            <div className="text-destructive"><span className="font-medium">Error:</span> {result.error}</div>
          )}
          <div className="flex justify-end gap-2 pt-2">
            {!isSuccess && (
              <Button size="sm" variant="outline" onClick={onClose}>Retry</Button>
            )}
            <Button size="sm" onClick={onClose}>Close</Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}