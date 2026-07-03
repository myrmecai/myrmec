// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  connectionConfigApi,
  type ConnectionConfigVersion,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Link2, Plus, Save, Send, Trash2, Power, PowerOff, Archive } from 'lucide-react'
import { TYPE_LABELS, STATUS_COLORS } from '../shared/constants'

export function ConnectionDetail({ configId }: { configId: string }) {
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

  const publishMutation = useMutation({
    mutationFn: () => connectionConfigApi.publish(configId),
    onSuccess: invalidate,
  })

  const discardMutation = useMutation({
    mutationFn: () => connectionConfigApi.discardDraft(configId),
    onSuccess: invalidate,
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
        <Link to="/platform/connections" className="hover:underline">Connections</Link>
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

      {/* Zone 1: Identity */}
      <Card className="mb-6">
        <CardHeader><CardTitle className="text-base">Identity & Metadata</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Type</dt><dd>{TYPE_LABELS[config.type] || config.type}</dd></div>
            <div><dt className="text-muted-foreground">Scope</dt><dd>{config.scope}</dd></div>
            <div><dt className="text-muted-foreground">Credential Secret</dt><dd>{config.credentialSecretId || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(config.createdAt).toLocaleString()}</dd></div>
            {config.publishedAt && (
              <div><dt className="text-muted-foreground">Published</dt><dd>{new Date(config.publishedAt).toLocaleString()}</dd></div>
            )}
          </dl>
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
            <Button onClick={() => createDraftMutation.mutate()} disabled={createDraftMutation.isPending}>
              <Plus className="h-4 w-4 mr-2" /> New Draft Version
            </Button>
          </CardContent>
        </Card>
      )}

      {hasDraft && (
        <DraftSection
          configId={configId}
          version={draftVersion!}
          onPublish={() => publishMutation.mutate()}
          onDiscard={() => { if (confirm('Discard this draft?')) discardMutation.mutate() }}
          publishing={publishMutation.isPending}
          discarding={discardMutation.isPending}
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
          <Button variant="ghost" className="text-destructive" onClick={() => { if (confirm(`Archive "${config.name}"?`)) archiveMutation.mutate() }} disabled={archiveMutation.isPending}>
            <Archive className="h-4 w-4 mr-2" /> Archive
          </Button>
        )}
      </div>
    </ContentAreaLayout>
  )
}

function DraftSection({
  configId,
  version,
  onPublish,
  onDiscard,
  publishing,
  discarding,
}: {
  configId: string
  version: ConnectionConfigVersion
  onPublish: () => void
  onDiscard: () => void
  publishing: boolean
  discarding: boolean
}) {
  const queryClient = useQueryClient()
  const [url, setUrl] = useState(version.url ?? '')

  const saveMutation = useMutation({
    mutationFn: () => connectionConfigApi.updateDraft(configId, { url: url || undefined }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-config-draft', configId] }),
  })

  const dirty = url !== (version.url ?? '')

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
          <Button size="sm" onClick={onPublish} disabled={publishing}>
            <Send className="h-4 w-4 mr-2" /> {publishing ? 'Publishing…' : 'Publish'}
          </Button>
          <Button size="sm" variant="ghost" className="text-destructive" onClick={onDiscard} disabled={discarding}>
            <Trash2 className="h-4 w-4 mr-2" /> {discarding ? 'Discarding…' : 'Discard'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}