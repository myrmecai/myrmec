// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect, useMemo } from 'react'
import {
  knowledgeProviderApi,
  knowledgeSourceApi,
  connectionConfigApi,
  type KnowledgeProvider,
  type KnowledgeProviderVersion,
  type KnowledgeSource,
  type CreateKnowledgeSourceRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Database,
  Plus,
  Trash2,
  Pencil,
} from 'lucide-react'
import { STATUS_COLORS } from '../shared/constants'
import { ConnectionConfigSelect } from '@/features/platform/connections/components/ConnectionConfigSelect'
import { RequiredMark, DraftRequiredMark } from '@/components/ui/required-marks'
import { dialogService } from '@/services/dialog-service'

export function KnowledgeProviderDetail({ providerId, projectId }: { providerId: string; projectId?: string }) {
  const queryClient = useQueryClient()

  const { data: provider, isLoading } = useQuery({
    queryKey: ['knowledge-provider', providerId],
    queryFn: () => knowledgeProviderApi.get(providerId),
  })

  // Fetch connection configs to resolve connectionConfigId to name
  const { data: connectionConfigs } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })
  const connectionConfigNameById = useMemo(() => {
    const map: Record<string, string> = {}
    for (const c of connectionConfigs ?? []) {
      map[c.id] = c.name
    }
    return map
  }, [connectionConfigs])

  const { data: publishedVersion } = useQuery({
    queryKey: ['knowledge-provider-published', providerId],
    queryFn: () => knowledgeProviderApi.getPublishedVersion(providerId),
    retry: false,
    enabled: !!provider,
  })

  const { data: draftVersion } = useQuery({
    queryKey: ['knowledge-provider-draft', providerId],
    queryFn: () => knowledgeProviderApi.getDraftVersion(providerId),
    retry: false,
  })

  const { data: sources } = useQuery({
    queryKey: ['knowledge-sources', providerId],
    queryFn: () => knowledgeSourceApi.listByProvider(providerId),
    enabled: !!provider,
  })

  const { data: versions } = useQuery({
    queryKey: ['knowledge-provider-versions', providerId],
    queryFn: () => knowledgeProviderApi.getVersions(providerId),
    enabled: !!provider,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider-published', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider-draft', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-sources', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider-versions', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
  }

  const createDraftMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.createDraft(providerId, {}),
    onSuccess: invalidate,
  })

  const publishMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.publish(providerId),
    onSuccess: () => {
      // Remove draft from cache so UI switches to Published Version
      queryClient.removeQueries({ queryKey: ['knowledge-provider-draft', providerId] })
      // Refetch published version and provider immediately
      queryClient.refetchQueries({ queryKey: ['knowledge-provider-published', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-provider', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-sources', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-provider-versions', providerId] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
    },
  })

  const discardMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.discardDraft(providerId),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['knowledge-provider-draft', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-provider', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-provider-published', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-sources', providerId] })
      queryClient.refetchQueries({ queryKey: ['knowledge-provider-versions', providerId] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.disable(providerId),
    onSuccess: () => {
      queryClient.refetchQueries({ queryKey: ['knowledge-provider', providerId] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
    },
  })

  const reenableMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.reenable(providerId),
    onSuccess: () => {
      queryClient.refetchQueries({ queryKey: ['knowledge-provider', providerId] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
    },
  })

  const archiveMutation = useMutation({
    mutationFn: async () => {
      const confirmed = await dialogService.showConfirmDialog({
        title: 'Archive Provider',
        message: 'Are you sure you want to archive this provider?',
        severity: 'warning',
        type: 'warning',
        confirmLabel: 'Archive',
        cancelLabel: 'Cancel',
      })
      if (!confirmed) return
      return knowledgeProviderApi.archive(providerId)
    },
    onSuccess: () => {
      queryClient.refetchQueries({ queryKey: ['knowledge-provider', providerId] })
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
    },
  })

  const cloneMutation = useMutation({
    mutationFn: (versionId: string) => knowledgeProviderApi.cloneVersion(providerId, versionId),
    onSuccess: invalidate,
  })

  const [zone1Editing, setZone1Editing] = useState(false)
  const [sourceDialogOpen, setSourceDialogOpen] = useState(false)
  const [editingSource, setEditingSource] = useState<KnowledgeSource | null>(null)
  const [deleteSource, setDeleteSource] = useState<KnowledgeSource | null>(null)
  const [activeTab, setActiveTab] = useState<'details' | 'sources' | 'history' | 'audit'>('details')

  if (isLoading || !provider) {
    return <div className="p-8 text-muted-foreground">Loading knowledge provider…</div>
  }

  const hasDraft = !!draftVersion
  const hasPublished = !!publishedVersion

  return (
    <ContentAreaLayout maxWidth="56rem">
      {/* Breadcrumb — hidden when rendered inside project context (projectId provided) */}
      {!projectId && (
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/knowledge-providers" className="hover:underline">
          Knowledge Providers
        </Link>
        <span>/</span>
        <span className="text-foreground font-medium">{provider.name}</span>
      </div>
      )}

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <Database className="h-6 w-6 text-muted-foreground" />
          <div>
            <h1 className="text-2xl font-bold">{provider.name}</h1>
            {provider.description && <p className="text-muted-foreground">{provider.description}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{provider.type}</Badge>
          <div className="flex items-center gap-1">
            <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[provider.status] || 'bg-gray-400'}`} />
            <span className="text-sm">{provider.status}</span>
          </div>
        </div>
      </div>

      {/* Tab headers */}
      <div className="flex gap-4 border-b mb-4">
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'details' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('details')}
        >Details</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'sources' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('sources')}
        >Knowledge Sources</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'history' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('history')}
        >Version History</button>
        <button
          className={`pb-2 text-sm font-medium ${activeTab === 'audit' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
          onClick={() => setActiveTab('audit')}
        >Audit Log</button>
      </div>

      {/* Details tab */}
      {activeTab === 'details' && (
        <>
      {/* Zone 1: Identity */}
      <Zone1Section
        provider={provider}
        editing={zone1Editing}
        onEdit={() => setZone1Editing(true)}
        onCancel={() => setZone1Editing(false)}
        onSave={(name, description) => {
          knowledgeProviderApi.update(providerId, { name, description }).then(() => {
            invalidate()
            setZone1Editing(false)
          })
        }}
        lifecycle={
          <div className="flex items-center gap-2">
            {provider.status === 'ACTIVE' && (
              <Button size="sm" variant="outline" onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Disable Knowledge Provider',
                  message: `Disable "${provider.name}"? Projects will stop retrieving knowledge from this provider until it is re-enabled.`,
                  severity: 'warning',
                  type: 'warning',
                  confirmLabel: 'Disable',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) disableMutation.mutate()
              }} disabled={disableMutation.isPending}>
                Disable
              </Button>
            )}
            {provider.status === 'DISABLED' && (
              <>
                <Button size="sm" variant="outline" onClick={async () => {
                  const confirmed = await dialogService.showConfirmDialog({
                    title: 'Re-enable Knowledge Provider',
                    message: `Re-enable "${provider.name}"? Projects will immediately start retrieving knowledge from this provider again.`,
                    severity: 'info',
                    type: 'warning',
                    confirmLabel: 'Re-enable',
                    cancelLabel: 'Cancel',
                  })
                  if (confirmed) reenableMutation.mutate()
                }} disabled={reenableMutation.isPending}>
                  Re-enable
                </Button>
                <Button size="sm" variant="outline" onClick={() => archiveMutation.mutate()} disabled={archiveMutation.isPending}>
                  Archive
                </Button>
              </>
            )}
          </div>
        }
      />

      {/* Zone 2: Published Version */}
      {hasPublished && !hasDraft && (
        <PublishedVersionSection
          version={publishedVersion!}
          connectionConfigNameById={connectionConfigNameById}
          onNewDraft={() => createDraftMutation.mutate()}
          newVersionPending={createDraftMutation.isPending}
          hideNewDraft={zone1Editing}
        />
      )}

      {/* Zone 2: Draft */}
      {hasDraft && (
        <DraftSection
          providerId={providerId}
          version={draftVersion!}
          onPublish={async () => {
            const confirmed = await dialogService.showConfirmDialog({
              title: 'Publish Knowledge Provider',
              message: `Publish version ${draftVersion?.versionNumber ?? ''} of "${provider.name}"? The published version becomes live immediately and cannot be edited — open a new draft to change it.`,
              severity: 'warning',
              type: 'warning',
              confirmLabel: 'Publish',
              cancelLabel: 'Cancel',
            })
            if (confirmed) publishMutation.mutate()
          }}
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
        />
      )}

      {!hasPublished && !hasDraft && (
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="text-base">No Version Yet</CardTitle>
            <CardDescription>Create a draft to configure and publish this provider.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => createDraftMutation.mutate()} disabled={createDraftMutation.isPending}>
              <Plus className="h-4 w-4 mr-2" /> Create First Draft
            </Button>
          </CardContent>
        </Card>
      )}

      </>
      )}

      {/* Knowledge Sources tab */}
      {activeTab === 'sources' && (
      <KnowledgeSourcesSection
        sources={sources ?? []}
        hasDraft={hasDraft}
        onAdd={() => { setEditingSource(null); setSourceDialogOpen(true) }}
        onEdit={(s) => { setEditingSource(s); setSourceDialogOpen(true) }}
        onDelete={(s) => setDeleteSource(s)}
      />

      )}

      {/* Version History tab */}
      {activeTab === 'history' && (
      <>
      {/* Version History */}
      {versions && versions.length > 0 && (
        <Card className="mb-6">
          <CardHeader><CardTitle className="text-base">Version History</CardTitle></CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Version</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Published</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {versions.map((v) => (
                  <TableRow key={v.id}>
                    <TableCell>v{v.versionNumber}</TableCell>
                    <TableCell><Badge variant="outline">{v.status}</Badge></TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {v.publishedAt ? new Date(v.publishedAt).toLocaleString() : '—'}
                    </TableCell>
                    <TableCell>
                      {v.status === 'ARCHIVED' && !hasDraft && (
                        <Button size="sm" variant="outline" onClick={() => cloneMutation.mutate(v.id)}>
                          Clone as Draft
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
      </>
      )}

      {/* Audit Log tab (placeholder) */}
      {activeTab === 'audit' && (
        <Card className="mb-6">
          <CardHeader><CardTitle className="text-base">Audit Log</CardTitle></CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-center py-4">Audit log entries will appear here.</p>
          </CardContent>
        </Card>
      )}

      {/* Delete Source Confirmation Dialog */}
      {deleteSource && (
        <Dialog open onOpenChange={(o) => { if (!o) setDeleteSource(null) }}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>Delete Source</DialogTitle>
              <DialogDescription>
                Delete {deleteSource.name}? This source will be removed from the provider when the version is published.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setDeleteSource(null)}>Cancel</Button>
              <Button variant="destructive" onClick={() => {
                knowledgeSourceApi.delete(deleteSource.id).then(() => {
                  invalidate()
                  setDeleteSource(null)
                })
              }}>Delete</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      {/* Add/Edit Source Dialog */}
      {sourceDialogOpen && (
        <AddEditKnowledgeSourceDialog
          open={sourceDialogOpen}
          source={editingSource}
          onClose={() => setSourceDialogOpen(false)}
          onSave={async (data) => {
            if (editingSource) {
              await knowledgeSourceApi.update(editingSource.id, data)
            } else {
              await knowledgeSourceApi.create(providerId, data)
            }
            invalidate()
            setSourceDialogOpen(false)
          }}
        />
      )}
    </ContentAreaLayout>
  )
}

// --- Zone 1 Section ---

function Zone1Section({
  provider,
  editing,
  onEdit,
  onCancel,
  onSave,
  lifecycle,
}: {
  provider: KnowledgeProvider
  editing: boolean
  onEdit: () => void
  onCancel: () => void
  onSave: (name: string, description: string) => void
  lifecycle?: React.ReactNode
}) {
  const [name, setName] = useState(provider.name)
  const [description, setDescription] = useState(provider.description ?? '')

  useEffect(() => {
    setName(provider.name)
    setDescription(provider.description ?? '')
  }, [provider.name, provider.description])

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Identity &amp; Metadata</CardTitle>
          {!editing && (
            <div className="flex items-center gap-2">
              {lifecycle}
              <Button size="sm" variant="outline" onClick={onEdit}>Edit</Button>
            </div>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {editing ? (
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="kp-name">Name</Label>
              <Input id="kp-name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="kp-description">Description</Label>
              <Input id="kp-description" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="type">Type</Label>
              <Select value={provider.type} disabled>
                <SelectTrigger id="type"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="EXTERNAL">External (HTTP)</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">Type cannot be changed after creation.</p>
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={() => onSave(name, description)}>Save</Button>
              <Button size="sm" variant="outline" onClick={onCancel}>Cancel</Button>
            </div>
          </div>
        ) : (
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Name</dt><dd>{provider.name}</dd></div>
            <div><dt className="text-muted-foreground">Type</dt><dd>{provider.type}</dd></div>
            <div><dt className="text-muted-foreground">Description</dt><dd>{provider.description || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Status</dt><dd>{provider.status}</dd></div>
            <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(provider.createdAt).toLocaleString()}</dd></div>
            {provider.publishedAt && (
              <div><dt className="text-muted-foreground">Published</dt><dd>{new Date(provider.publishedAt).toLocaleString()}</dd></div>
            )}
          </dl>
        )}
      </CardContent>
    </Card>
  )
}

// --- Published Version Section ---

function PublishedVersionSection({
  version,
  connectionConfigNameById,
  onNewDraft,
  newVersionPending,
  hideNewDraft,
}: {
  version: KnowledgeProviderVersion
  connectionConfigNameById: Record<string, string>
  onNewDraft: () => void
  newVersionPending: boolean
  hideNewDraft: boolean
}) {
  const config = version.config as Record<string, unknown> | null
  const responseMapping = config?.responseMapping as Record<string, string> | undefined

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Published Version (v{version.versionNumber})</CardTitle>
            <CardDescription>{version.publishedAt ? new Date(version.publishedAt).toLocaleString() : '—'}</CardDescription>
          </div>
          <Badge>Published</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div><dt className="text-muted-foreground">Connection Config</dt><dd>{version.connectionConfigId ? (connectionConfigNameById[version.connectionConfigId] ?? version.connectionConfigId) : '—'}</dd></div>
          {config?.maxTopK != null && (
            <div><dt className="text-muted-foreground">Max Top-K</dt><dd>{String(config.maxTopK)}</dd></div>
          )}
          {config?.similarityThreshold != null && (
            <div><dt className="text-muted-foreground">Similarity Threshold</dt><dd>{String(config.similarityThreshold)}</dd></div>
          )}
          {config?.timeoutMs != null && (
            <div><dt className="text-muted-foreground">Timeout (ms)</dt><dd>{String(config.timeoutMs)}</dd></div>
          )}
        </div>
        {responseMapping && (
          <div className="space-y-1 text-sm">
            <Label className="text-muted-foreground">Response Mapping</Label>
            <div className="grid grid-cols-2 gap-2">
              {responseMapping.hitsPath && <div><span className="text-muted-foreground">Hits Path:</span> {responseMapping.hitsPath}</div>}
              {responseMapping.passagePath && <div><span className="text-muted-foreground">Passage Path:</span> {responseMapping.passagePath}</div>}
              {responseMapping.sourceNamePath && <div><span className="text-muted-foreground">Source Name Path:</span> {responseMapping.sourceNamePath}</div>}
              {responseMapping.locatorPath && <div><span className="text-muted-foreground">Locator Path:</span> {responseMapping.locatorPath}</div>}
              {responseMapping.scorePath && <div><span className="text-muted-foreground">Score Path:</span> {responseMapping.scorePath}</div>}
            </div>
          </div>
        )}
        {!hideNewDraft && (
          <Button onClick={onNewDraft} disabled={newVersionPending}>
            <Plus className="h-4 w-4 mr-2" /> {newVersionPending ? 'Creating…' : 'New Draft Version'}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

// --- Draft Section ---

function DraftSection({
  providerId,
  version,
  onPublish,
  onDiscard,
  publishing,
  discarding,
}: {
  providerId: string
  version: KnowledgeProviderVersion
  onPublish: () => void
  onDiscard: () => void
  publishing: boolean
  discarding: boolean
}) {
  const queryClient = useQueryClient()

  const initialConfig = version.config as Record<string, unknown> | null
  const initialMapping = initialConfig?.responseMapping as Record<string, string> | undefined

  const [connectionConfigId, setConnectionConfigId] = useState(version.connectionConfigId ?? '')
  const [maxTopK, setMaxTopK] = useState(String(initialConfig?.maxTopK ?? ''))
  const [similarityThreshold, setSimilarityThreshold] = useState(String(initialConfig?.similarityThreshold ?? ''))
  const [timeoutMs, setTimeoutMs] = useState(String(initialConfig?.timeoutMs ?? ''))
  const [hitsPath, setHitsPath] = useState(initialMapping?.hitsPath ?? '')
  const [passagePath, setPassagePath] = useState(initialMapping?.passagePath ?? '')
  const [sourceNamePath, setSourceNamePath] = useState(initialMapping?.sourceNamePath ?? '')
  const [locatorPath, setLocatorPath] = useState(initialMapping?.locatorPath ?? '')
  const [scorePath, setScorePath] = useState(initialMapping?.scorePath ?? '')

  useEffect(() => {
    const cfg = version.config as Record<string, unknown> | null
    const mapping = cfg?.responseMapping as Record<string, string> | undefined
    setConnectionConfigId(version.connectionConfigId ?? '')
    setMaxTopK(String(cfg?.maxTopK ?? ''))
    setSimilarityThreshold(String(cfg?.similarityThreshold ?? ''))
    setTimeoutMs(String(cfg?.timeoutMs ?? ''))
    setHitsPath(mapping?.hitsPath ?? '')
    setPassagePath(mapping?.passagePath ?? '')
    setSourceNamePath(mapping?.sourceNamePath ?? '')
    setLocatorPath(mapping?.locatorPath ?? '')
    setScorePath(mapping?.scorePath ?? '')
  }, [version])

  const config: Record<string, unknown> = {
    maxTopK: maxTopK ? parseInt(maxTopK) : null,
    similarityThreshold: similarityThreshold ? parseFloat(similarityThreshold) : null,
    timeoutMs: timeoutMs ? parseInt(timeoutMs) : null,
    responseMapping: { hitsPath, passagePath, sourceNamePath, locatorPath, scorePath },
  }

  const saveMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.updateDraft(providerId, {
      connectionConfigId: connectionConfigId || null,
      config,
    }),
    onSuccess: () => queryClient.refetchQueries({ queryKey: ['knowledge-provider-draft', providerId] }),
  })

  const dirty =
    connectionConfigId !== (version.connectionConfigId ?? '') ||
    maxTopK !== String(initialConfig?.maxTopK ?? '') ||
    similarityThreshold !== String(initialConfig?.similarityThreshold ?? '') ||
    timeoutMs !== String(initialConfig?.timeoutMs ?? '') ||
    hitsPath !== (initialMapping?.hitsPath ?? '') ||
    passagePath !== (initialMapping?.passagePath ?? '') ||
    sourceNamePath !== (initialMapping?.sourceNamePath ?? '') ||
    locatorPath !== (initialMapping?.locatorPath ?? '') ||
    scorePath !== (initialMapping?.scorePath ?? '')

  const canPublish = hitsPath.trim() !== '' && passagePath.trim() !== '' &&
    sourceNamePath.trim() !== '' && locatorPath.trim() !== ''

  return (
    <Card className="mb-6 border-primary/30">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Draft (v{version.versionNumber})</CardTitle>
            <CardDescription>Configure and publish this draft</CardDescription>
          </div>
          <Badge variant="secondary">Draft</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>Connection Config<DraftRequiredMark /></Label>
          <ConnectionConfigSelect
            value={connectionConfigId || null}
            onChange={(id) => setConnectionConfigId(id ?? '')}
          />
          <p className="text-xs text-muted-foreground">
            Connection config holding the credentials for the retrieval endpoint.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="maxTopK">Max Top-K</Label>
            <Input id="maxTopK" value={maxTopK} onChange={(e) => setMaxTopK(e.target.value)} placeholder="50" type="number" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="similarityThreshold">Similarity Threshold</Label>
            <Input id="similarityThreshold" value={similarityThreshold} onChange={(e) => setSimilarityThreshold(e.target.value)} placeholder="0.2" type="number" step="0.01" />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="timeoutMs">Timeout (ms)</Label>
          <Input id="timeoutMs" value={timeoutMs} onChange={(e) => setTimeoutMs(e.target.value)} placeholder="5000" type="number" />
        </div>
        <div className="space-y-3 rounded-md border p-3">
          <Label className="text-sm font-semibold">Response Mapping</Label>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label htmlFor="hitsPath">Hits Path<RequiredMark /></Label>
              <Input id="hitsPath" value={hitsPath} onChange={(e) => setHitsPath(e.target.value)} placeholder="$.results" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="passagePath">Passage Path<RequiredMark /></Label>
              <Input id="passagePath" value={passagePath} onChange={(e) => setPassagePath(e.target.value)} placeholder="$.text" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="sourceNamePath">Source Name Path<RequiredMark /></Label>
              <Input id="sourceNamePath" value={sourceNamePath} onChange={(e) => setSourceNamePath(e.target.value)} placeholder="$.source" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="locatorPath">Locator Path<RequiredMark /></Label>
              <Input id="locatorPath" value={locatorPath} onChange={(e) => setLocatorPath(e.target.value)} placeholder="$.url" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="scorePath">Score Path</Label>
              <Input id="scorePath" value={scorePath} onChange={(e) => setScorePath(e.target.value)} placeholder="$.score" />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">
            Fields marked with <span className="text-red-500">*</span> are required before publishing.
          </p>
        </div>

        <div className="flex items-center gap-2 pt-2">
          <Button size="sm" onClick={() => saveMutation.mutate()} disabled={!dirty || saveMutation.isPending}>
            {saveMutation.isPending ? 'Saving…' : 'Save Draft'}
          </Button>
          <Button
            size="sm"
            onClick={onPublish}
            disabled={publishing || !canPublish || dirty}
            title={dirty ? 'Save your changes before publishing.' : !canPublish ? 'Fill all fields marked with * before publishing.' : undefined}
          >
            {publishing ? 'Publishing…' : 'Publish'}
          </Button>
          <Button size="sm" variant="ghost" className="text-destructive" onClick={onDiscard} disabled={discarding}>
            <Trash2 className="h-4 w-4 mr-2" /> {discarding ? 'Discarding…' : 'Discard'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

// --- Knowledge Sources Section ---

function KnowledgeSourcesSection({
  sources,
  hasDraft,
  onAdd,
  onEdit,
  onDelete,
}: {
  sources: KnowledgeSource[]
  hasDraft: boolean
  onAdd: () => void
  onEdit: (s: KnowledgeSource) => void
  onDelete: (s: KnowledgeSource) => void
}) {
  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Knowledge Sources</CardTitle>
          {hasDraft && (
            <Button size="sm" onClick={onAdd}>
              <Plus className="h-4 w-4 mr-2" /> Add
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {sources.length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Method</TableHead>
                <TableHead>Path</TableHead>
                <TableHead>Description</TableHead>
                {hasDraft && <TableHead>Actions</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {sources.map((s) => {
                const cfg = s.config as Record<string, unknown> | null
                const httpConfig = cfg?.httpConfig as Record<string, unknown> | undefined
                return (
                  <TableRow key={s.id}>
                    <TableCell className="font-medium">{s.name}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{(httpConfig?.method as string) ?? '—'}</Badge>
                    </TableCell>
                    <TableCell className="text-sm font-mono">{(httpConfig?.path as string) ?? '—'}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{s.description || '—'}</TableCell>
                    {hasDraft && (
                      <TableCell>
                        <div className="flex gap-1">
                          <Button size="sm" variant="ghost" onClick={() => onEdit(s)}>
                            <Pencil className="h-3 w-3" /> Edit
                          </Button>
                          <Button size="sm" variant="ghost" className="text-destructive" onClick={() => onDelete(s)}>
                            <Trash2 className="h-3 w-3" /> Delete
                          </Button>
                        </div>
                      </TableCell>
                    )}
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        ) : (
          <p className="text-muted-foreground text-center py-4">
            {hasDraft
              ? 'No knowledge sources yet. Click Add to create one.'
              : 'No knowledge sources. Create a new Provider version to edit this configuration.'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

// --- Add/Edit Knowledge Source Dialog ---

function AddEditKnowledgeSourceDialog({
  open,
  source,
  onClose,
  onSave,
}: {
  open: boolean
  source: KnowledgeSource | null
  onClose: () => void
  onSave: (data: CreateKnowledgeSourceRequest) => Promise<void>
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [path, setPath] = useState('')
  const [method, setMethod] = useState('GET')
  const [queryParam, setQueryParam] = useState('query')
  const [topKOverride, setTopKOverride] = useState('')

  useEffect(() => {
    if (source) {
      setName(source.name)
      setDescription(source.description ?? '')
      const cfg = source.config as Record<string, unknown> | null
      const httpConfig = cfg?.httpConfig as Record<string, unknown> | undefined
      const queryConfig = cfg?.queryConfig as Record<string, unknown> | undefined
      setPath((httpConfig?.path as string) ?? '')
      setMethod((httpConfig?.method as string) ?? 'GET')
      setQueryParam((queryConfig?.queryParam as string) ?? 'query')
      setTopKOverride(cfg?.topKOverride != null ? String(cfg.topKOverride) : '')
    } else {
      setName('')
      setDescription('')
      setPath('')
      setMethod('GET')
      setQueryParam('query')
      setTopKOverride('')
    }
  }, [source, open])

  const handleSave = () => {
    const config: Record<string, unknown> = {
      httpConfig: { path, method },
      queryConfig: { queryParam },
      topKOverride: topKOverride ? parseInt(topKOverride) : null,
      similarityThresholdOverride: null,
      responseMappingOverride: null,
    }
    onSave({ name, description: description || undefined, config })
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{source ? 'Edit Knowledge Source' : 'Add Knowledge Source'}</DialogTitle>
          <DialogDescription>Configure a knowledge source for this provider</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="ks-name">Name<RequiredMark /></Label>
            <Input id="ks-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="ks-description">Description</Label>
            <Input id="ks-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="ks-path">Path<RequiredMark /></Label>
            <Input id="ks-path" value={path} onChange={(e) => setPath(e.target.value)} placeholder="/api/v1/retrieval" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="ks-method">Method</Label>
              <Select value={method} onValueChange={setMethod}>
                <SelectTrigger id="ks-method"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="GET">GET</SelectItem>
                  <SelectItem value="POST">POST</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="ks-queryParam">Query Param</Label>
              <Input id="ks-queryParam" value={queryParam} onChange={(e) => setQueryParam(e.target.value)} />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="ks-topK">Top-K Override</Label>
            <Input id="ks-topK" value={topKOverride} onChange={(e) => setTopKOverride(e.target.value)} type="number" placeholder="(provider default)" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSave} disabled={!name || !path}>Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}