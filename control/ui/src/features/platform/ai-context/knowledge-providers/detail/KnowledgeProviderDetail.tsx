// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeProviderApi,
  connectionConfigApi,
  knowledgeSourceApi,
  type KnowledgeProviderVersion,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
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
  Database,
  Plus,
  Send,
  Trash2,
  Power,
  PowerOff,
  AlertCircle,
} from 'lucide-react'
import { STATUS_COLORS } from '../shared/constants'

export function KnowledgeProviderDetail({ providerId }: { providerId: string }) {
  const queryClient = useQueryClient()

  const { data: provider, isLoading } = useQuery({
    queryKey: ['knowledge-provider', providerId],
    queryFn: () => knowledgeProviderApi.get(providerId),
  })

  const { data: publishedVersion } = useQuery({
    queryKey: ['knowledge-provider-published', providerId],
    queryFn: () => knowledgeProviderApi.getPublishedVersion(providerId),
    enabled: !!provider?.currentVersionId,
  })

  const { data: draftVersion } = useQuery({
    queryKey: ['knowledge-provider-draft', providerId],
    queryFn: () => knowledgeProviderApi.getDraftVersion(providerId),
    retry: false,
  })

  const { data: sources } = useQuery({
    queryKey: ['knowledge-sources'],
    queryFn: knowledgeSourceApi.list,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider-published', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-provider-draft', providerId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
  }

  const createDraftMutation = useMutation({
    mutationFn: (data: { connectionConfigId?: string | null; config?: Record<string, unknown> }) =>
      knowledgeProviderApi.createDraft(providerId, data),
    onSuccess: invalidate,
  })

  const publishMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.publish(providerId),
    onSuccess: invalidate,
  })

  const discardMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.discardDraft(providerId),
    onSuccess: invalidate,
  })

  const disableMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.disable(providerId),
    onSuccess: invalidate,
  })

  const reenableMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.reenable(providerId),
    onSuccess: invalidate,
  })

  const [createDraftOpen, setCreateDraftOpen] = useState(false)

  if (isLoading || !provider) {
    return <div className="p-8 text-muted-foreground">Loading knowledge provider…</div>
  }

  const linkedSources = sources?.filter((s) => s.providerVersionId === publishedVersion?.id) ?? []

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/knowledge-providers" className="hover:underline">
          Knowledge Providers
        </Link>
        <span>/</span>
        <span className="text-foreground font-medium">{provider.name}</span>
      </div>

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

      {/* Zone 1: Identity */}
      <Card className="mb-6">
        <CardHeader><CardTitle className="text-base">Identity & Metadata</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Type</dt><dd>{provider.type}</dd></div>
            <div><dt className="text-muted-foreground">Scope</dt><dd>{provider.scope}</dd></div>
            <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(provider.createdAt).toLocaleString()}</dd></div>
            <div><dt className="text-muted-foreground">Updated</dt><dd>{new Date(provider.updatedAt).toLocaleString()}</dd></div>
            {provider.publishedAt && (
              <div><dt className="text-muted-foreground">Published</dt><dd>{new Date(provider.publishedAt).toLocaleString()}</dd></div>
            )}
          </dl>
        </CardContent>
      </Card>

      {/* Zone 2: Version */}
      {publishedVersion && !draftVersion && (
        <Card className="mb-6">
          <CardHeader>
            <div className="flex items-center justify-between">
              <div>
                <CardTitle className="text-base">Published Version (v{publishedVersion.versionNumber})</CardTitle>
                <CardDescription>{publishedVersion.publishedAt ? new Date(publishedVersion.publishedAt).toLocaleString() : '—'}</CardDescription>
              </div>
              <Badge>Published</Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><dt className="text-muted-foreground">Connection Config</dt><dd>{publishedVersion.connectionConfigId || '—'}</dd></div>
            </div>
            {publishedVersion.config && (
              <div>
                <Label className="mb-2 block">Config</Label>
                <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                  {JSON.stringify(publishedVersion.config, null, 2)}
                </pre>
              </div>
            )}
            <Button onClick={() => setCreateDraftOpen(true)}>
              <Plus className="h-4 w-4 mr-2" /> New Draft Version
            </Button>
          </CardContent>
        </Card>
      )}

      {draftVersion && (
        <DraftSection
          providerId={providerId}
          version={draftVersion}
          onPublish={() => publishMutation.mutate()}
          onDiscard={() => { if (confirm('Discard this draft?')) discardMutation.mutate() }}
          publishing={publishMutation.isPending}
          discarding={discardMutation.isPending}
        />
      )}

      {!publishedVersion && !draftVersion && (
        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="text-base">No Version Yet</CardTitle>
            <CardDescription>Create a draft to configure and publish this provider.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => setCreateDraftOpen(true)}><Plus className="h-4 w-4 mr-2" /> Create First Draft</Button>
          </CardContent>
        </Card>
      )}

      {/* Linked Knowledge Sources */}
      {linkedSources.length > 0 && (
        <Card className="mb-6">
          <CardHeader><CardTitle className="text-base">Linked Knowledge Sources</CardTitle></CardHeader>
          <CardContent>
            <div className="space-y-2">
              {linkedSources.map((s) => (
                <div key={s.id} className="flex items-center justify-between text-sm">
                  <span>{s.name}</span>
                  <Badge variant="outline">{s.status}</Badge>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Actions */}
      <div className="flex items-center gap-2 mt-6">
        {provider.status === 'ACTIVE' && (
          <Button variant="outline" onClick={() => disableMutation.mutate()} disabled={disableMutation.isPending}>
            <PowerOff className="h-4 w-4 mr-2" /> Disable
          </Button>
        )}
        {provider.status === 'DISABLED' && (
          <Button variant="outline" onClick={() => reenableMutation.mutate()} disabled={reenableMutation.isPending}>
            <Power className="h-4 w-4 mr-2" /> Re-enable
          </Button>
        )}
      </div>

      <CreateDraftDialog
        open={createDraftOpen}
        onOpenChange={setCreateDraftOpen}
        onCreate={(data) => createDraftMutation.mutate(data)}
        isPending={createDraftMutation.isPending}
      />
    </ContentAreaLayout>
  )
}

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
  const [connectionConfigId, setConnectionConfigId] = useState(version.connectionConfigId ?? '')

  const { data: connectionConfigs } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  const saveMutation = useMutation({
    mutationFn: () => knowledgeProviderApi.createDraft(providerId, {
      connectionConfigId: connectionConfigId || null,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-provider-draft', providerId] }),
  })

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
          <Label>Connection Config</Label>
          <Select value={connectionConfigId} onValueChange={setConnectionConfigId}>
            <SelectTrigger><SelectValue placeholder="Select a connection config" /></SelectTrigger>
            <SelectContent>
              {(connectionConfigs ?? []).filter((c) => c.status === 'ACTIVE').map((c) => (
                <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-2 pt-2">
          <Button size="sm" onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
            {saveMutation.isPending ? 'Saving…' : 'Save Draft'}
          </Button>
          <Button size="sm" onClick={onPublish} disabled={publishing}>
            <Send className="h-4 w-4 mr-2" /> {publishing ? 'Publishing…' : 'Publish'}
          </Button>
          <Button size="sm" variant="ghost" className="text-destructive" onClick={onDiscard} disabled={discarding}>
            <Trash2 className="h-4 w-4 mr-2" /> {discarding ? 'Discarding…' : 'Discard'}
          </Button>
          {saveMutation.error && (
            <span className="text-sm text-destructive"><AlertCircle className="h-3 w-3 inline mr-1" />{saveMutation.error.message}</span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function CreateDraftDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: { connectionConfigId?: string | null }) => void
  isPending: boolean
}) {
  const [connectionConfigId, setConnectionConfigId] = useState('')

  const { data: connectionConfigs } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({ connectionConfigId: connectionConfigId || null })
    setConnectionConfigId('')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Draft Version</DialogTitle>
          <DialogDescription>Select a connection config for this provider</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label>Connection Config</Label>
            <Select value={connectionConfigId} onValueChange={setConnectionConfigId}>
              <SelectTrigger><SelectValue placeholder="Select a connection config" /></SelectTrigger>
              <SelectContent>
                {(connectionConfigs ?? []).filter((c) => c.status === 'ACTIVE').map((c) => (
                  <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending}>{isPending ? 'Creating…' : 'Create Draft'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}