// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useNavigate, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  instructionAssetApi,
  connectionConfigApi,
  type SourceType,
  type Availability,
  type InstructionAssetVersion,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
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
  FileText,
  Save,
  Send,
  Trash2,
  Eye,
  Archive,
  Power,
  PowerOff,
  GitBranch,
  Plus,
  AlertCircle,
} from 'lucide-react'
import { CATEGORY_LABELS, STATUS_COLORS } from '../shared/constants'

export function InstructionAssetDetail({ assetId }: { assetId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: asset, isLoading } = useQuery({
    queryKey: ['instruction-asset', assetId],
    queryFn: () => instructionAssetApi.get(assetId),
  })

  const { data: publishedVersion } = useQuery({
    queryKey: ['instruction-asset-published', assetId],
    queryFn: () => instructionAssetApi.getPublishedVersion(assetId),
    enabled: !!asset?.currentVersionId,
  })

  const { data: draftVersion } = useQuery({
    queryKey: ['instruction-asset-draft', assetId],
    queryFn: () => instructionAssetApi.getDraftVersion(assetId),
    retry: false,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['instruction-asset', assetId] })
    queryClient.invalidateQueries({ queryKey: ['instruction-asset-published', assetId] })
    queryClient.invalidateQueries({ queryKey: ['instruction-asset-draft', assetId] })
    queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
  }

  const createDraftMutation = useMutation({
    mutationFn: (data: {
      sourceType: SourceType
      sourceDetails?: Record<string, unknown>
      connectionConfigId?: string | null
      applicability?: Record<string, unknown>
      availability?: Availability
      priority?: number
    }) => instructionAssetApi.createDraft(assetId, data),
    onSuccess: invalidate,
  })

  const publishMutation = useMutation({
    mutationFn: () => instructionAssetApi.publish(assetId),
    onSuccess: invalidate,
  })

  const discardMutation = useMutation({
    mutationFn: () => instructionAssetApi.discardDraft(assetId),
    onSuccess: invalidate,
  })

  const disableMutation = useMutation({
    mutationFn: () => instructionAssetApi.disable(assetId),
    onSuccess: invalidate,
  })

  const reenableMutation = useMutation({
    mutationFn: () => instructionAssetApi.reenable(assetId),
    onSuccess: invalidate,
  })

  const archiveMutation = useMutation({
    mutationFn: () => instructionAssetApi.archive(assetId),
    onSuccess: () => {
      invalidate()
      navigate({ to: '/platform/ai-context/instruction-assets' })
    },
  })

  const [createDraftOpen, setCreateDraftOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)

  if (isLoading || !asset) {
    return (
      <div className="p-8 text-muted-foreground">Loading instruction asset…</div>
    )
  }

  const hasDraft = !!draftVersion
  const hasPublished = !!publishedVersion

  return (
    <ContentAreaLayout maxWidth="56rem">
      {/* Breadcrumb */}
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/instruction-assets" className="hover:underline">
          Instruction Assets
        </Link>
        <span>/</span>
        <span className="text-foreground font-medium">{asset.name}</span>
      </div>

      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <FileText className="h-6 w-6 text-muted-foreground" />
          <div>
            <h1 className="text-2xl font-bold">{asset.name}</h1>
            {asset.description && (
              <p className="text-muted-foreground">{asset.description}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{CATEGORY_LABELS[asset.category] || asset.category}</Badge>
          <div className="flex items-center gap-1">
            <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[asset.status] || 'bg-gray-400'}`} />
            <span className="text-sm">{asset.status}</span>
          </div>
        </div>
      </div>

      {/* Zone 1: Identity & Metadata */}
      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="text-base">Identity & Metadata</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <dt className="text-muted-foreground">Scope</dt>
              <dd>{asset.scope}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Category</dt>
              <dd>{CATEGORY_LABELS[asset.category] || asset.category}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Created</dt>
              <dd>{new Date(asset.createdAt).toLocaleString()}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Updated</dt>
              <dd>{new Date(asset.updatedAt).toLocaleString()}</dd>
            </div>
            {asset.publishedAt && (
              <div>
                <dt className="text-muted-foreground">Published</dt>
                <dd>{new Date(asset.publishedAt).toLocaleString()}</dd>
              </div>
            )}
            {asset.createdBy && (
              <div>
                <dt className="text-muted-foreground">Created By</dt>
                <dd>{asset.createdBy}</dd>
              </div>
            )}
          </dl>
        </CardContent>
      </Card>

      {/* Zone 2: Behavioural Configuration (Version) */}
      {hasPublished && !hasDraft && (
        <PublishedVersionSection
          version={publishedVersion!}
          onNewDraft={() => setCreateDraftOpen(true)}
        />
      )}

      {hasDraft && (
        <DraftVersionSection
          assetId={assetId}
          version={draftVersion!}
          onPublish={() => publishMutation.mutate()}
          onDiscard={() => {
            if (confirm('Discard this draft? Unpublished changes are lost.')) {
              discardMutation.mutate()
            }
          }}
          onPreview={() => setPreviewOpen(true)}
          publishing={publishMutation.isPending}
          discarding={discardMutation.isPending}
        />
      )}

      {!hasPublished && !hasDraft && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">No Version Yet</CardTitle>
            <CardDescription>
              This asset has no published or draft version. Create a draft to get started.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => setCreateDraftOpen(true)}>
              <Plus className="h-4 w-4 mr-2" />
              Create First Draft
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Action buttons */}
      <div className="flex items-center gap-2 mt-6">
        {asset.status === 'ACTIVE' && (
          <Button variant="outline" onClick={() => disableMutation.mutate()} disabled={disableMutation.isPending}>
            <PowerOff className="h-4 w-4 mr-2" />
            Disable
          </Button>
        )}
        {asset.status === 'DISABLED' && (
          <Button variant="outline" onClick={() => reenableMutation.mutate()} disabled={reenableMutation.isPending}>
            <Power className="h-4 w-4 mr-2" />
            Re-enable
          </Button>
        )}
        {asset.status !== 'ARCHIVED' && (
          <Button
            variant="ghost"
            className="text-destructive"
            onClick={() => {
              if (confirm(`Archive "${asset.name}"? This can be restored later.`)) {
                archiveMutation.mutate()
              }
            }}
            disabled={archiveMutation.isPending}
          >
            <Archive className="h-4 w-4 mr-2" />
            Archive
          </Button>
        )}
      </div>

      {/* Create Draft Dialog */}
      <CreateDraftDialog
        open={createDraftOpen}
        onOpenChange={setCreateDraftOpen}
        onCreate={(data) => createDraftMutation.mutate(data)}
        isPending={createDraftMutation.isPending}
      />

      {/* Preview Dialog */}
      {previewOpen && draftVersion && (
        <PreviewDialog
          version={draftVersion}
          onOpenChange={setPreviewOpen}
        />
      )}
    </ContentAreaLayout>
  )
}

// --- Published Version Section ---

function PublishedVersionSection({
  version,
  onNewDraft,
}: {
  version: InstructionAssetVersion
  onNewDraft: () => void
}) {
  const content = (version.sourceDetails as { content?: string })?.content ?? ''

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Published Version (v{version.versionNumber})</CardTitle>
            <CardDescription>
              Published {version.publishedAt ? new Date(version.publishedAt).toLocaleString() : '—'}
            </CardDescription>
          </div>
          <Badge>Published</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-muted-foreground">Source Type</dt>
            <dd className="flex items-center gap-1">
              {version.sourceType === 'GIT' ? (
                <><GitBranch className="h-3 w-3" /> Git</>
              ) : (
                'Inline'
              )}
            </dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Availability</dt>
            <dd>{version.availability}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Priority</dt>
            <dd>{version.priority}</dd>
          </div>
          {version.estimatedTokens != null && (
            <div>
              <dt className="text-muted-foreground">Estimated Tokens</dt>
              <dd>{version.estimatedTokens}</dd>
            </div>
          )}
        </div>

        {version.sourceType === 'INLINE' && content && (
          <div>
            <Label className="mb-2 block">Content</Label>
            <pre className="text-sm bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-64 overflow-y-auto">
              {content}
            </pre>
          </div>
        )}

        {version.sourceType === 'GIT' && (
          <div className="text-sm space-y-1">
            <div><span className="text-muted-foreground">Connection Config:</span> {version.connectionConfigId || '—'}</div>
            {version.gitCommit && (
              <div><span className="text-muted-foreground">Git Commit:</span> {version.gitCommit}</div>
            )}
          </div>
        )}

        <div>
          <Label className="mb-2 block">Applicability</Label>
          <div className="flex gap-2">
            {Object.entries(version.applicability).map(([key, val]) => (
              <Badge key={key} variant="outline" className="text-xs">
                {key}: {String(val)}
              </Badge>
            ))}
          </div>
        </div>

        <Button onClick={onNewDraft}>
          <Plus className="h-4 w-4 mr-2" />
          New Draft Version
        </Button>
      </CardContent>
    </Card>
  )
}

// --- Draft Version Section ---

function DraftVersionSection({
  assetId,
  version,
  onPublish,
  onDiscard,
  onPreview,
  publishing,
  discarding,
}: {
  assetId: string
  version: InstructionAssetVersion
  onPublish: () => void
  onDiscard: () => void
  onPreview: () => void
  publishing: boolean
  discarding: boolean
}) {
  const queryClient = useQueryClient()
  const [content, setContent] = useState(
    (version.sourceDetails as { content?: string })?.content ?? '',
  )
  const [priority, setPriority] = useState(String(version.priority))
  const [availability, setAvailability] = useState<Availability>(version.availability)
  const [conversationApplicable, setConversationApplicable] = useState(
    (version.applicability as Record<string, unknown>)?.CONVERSATION === 'true' ||
    (version.applicability as Record<string, unknown>)?.CONVERSATION === true,
  )
  const [workflowApplicable, setWorkflowApplicable] = useState(
    (version.applicability as Record<string, unknown>)?.WORKFLOW === 'true' ||
    (version.applicability as Record<string, unknown>)?.WORKFLOW === true,
  )

  const saveMutation = useMutation({
    mutationFn: () =>
      instructionAssetApi.createDraft(assetId, {
        sourceType: version.sourceType,
        sourceDetails: { content },
        applicability: {
          CONVERSATION: String(conversationApplicable),
          WORKFLOW: String(workflowApplicable),
        },
        availability,
        priority: parseInt(priority) || 100,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-asset-draft', assetId] })
    },
  })

  const dirty =
    content !== ((version.sourceDetails as { content?: string })?.content ?? '') ||
    priority !== String(version.priority) ||
    availability !== version.availability

  return (
    <Card className="mb-6 border-primary/30">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Draft (v{version.versionNumber})</CardTitle>
            <CardDescription>Edit and publish this draft version</CardDescription>
          </div>
          <Badge variant="secondary">Draft</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Source Type */}
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-muted-foreground">Source Type</dt>
            <dd className="flex items-center gap-1">
              {version.sourceType === 'GIT' ? (
                <><GitBranch className="h-3 w-3" /> Git</>
              ) : (
                'Inline'
              )}
            </dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Version Number</dt>
            <dd>{version.versionNumber}</dd>
          </div>
        </div>

        {/* Content Editor (INLINE only) */}
        {version.sourceType === 'INLINE' && (
          <div className="space-y-2">
            <Label htmlFor="draft-content">Content</Label>
            <Textarea
              id="draft-content"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={12}
              className="font-mono text-sm"
              placeholder="Enter instruction content (markdown supported)…"
            />
          </div>
        )}

        {/* Git info (read-only in draft) */}
        {version.sourceType === 'GIT' && (
          <div className="text-sm space-y-1 bg-muted/30 rounded-md p-3">
            <div><span className="text-muted-foreground">Connection Config:</span> {version.connectionConfigId || '—'}</div>
            {version.gitCommit && (
              <div><span className="text-muted-foreground">Git Commit:</span> {version.gitCommit}</div>
            )}
          </div>
        )}

        {/* Applicability */}
        <div className="space-y-2">
          <Label>Applicability</Label>
          <div className="flex gap-4">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={conversationApplicable}
                onChange={(e) => setConversationApplicable(e.target.checked)}
              />
              CONVERSATION
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={workflowApplicable}
                onChange={(e) => setWorkflowApplicable(e.target.checked)}
              />
              WORKFLOW
            </label>
          </div>
        </div>

        {/* Availability + Priority */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label>Availability</Label>
            <Select value={availability} onValueChange={(v) => setAvailability(v as Availability)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="REQUIRED">Required</SelectItem>
                <SelectItem value="OPTIONAL">Optional</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="priority">Priority</Label>
            <Input
              id="priority"
              type="number"
              value={priority}
              onChange={(e) => setPriority(e.target.value)}
            />
          </div>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2 pt-2">
          <Button
            size="sm"
            onClick={() => saveMutation.mutate()}
            disabled={!dirty || saveMutation.isPending}
          >
            <Save className="h-4 w-4 mr-2" />
            {saveMutation.isPending ? 'Saving…' : 'Save Draft'}
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={onPreview}
          >
            <Eye className="h-4 w-4 mr-2" />
            Preview
          </Button>
          <Button
            size="sm"
            onClick={onPublish}
            disabled={publishing}
          >
            <Send className="h-4 w-4 mr-2" />
            {publishing ? 'Publishing…' : 'Publish'}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="text-destructive"
            onClick={onDiscard}
            disabled={discarding}
          >
            <Trash2 className="h-4 w-4 mr-2" />
            {discarding ? 'Discarding…' : 'Discard'}
          </Button>
          {saveMutation.error && (
            <span className="text-sm text-destructive">
              <AlertCircle className="h-3 w-3 inline mr-1" />
              {saveMutation.error.message}
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

// --- Create Draft Dialog ---

function CreateDraftDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: {
    sourceType: SourceType
    sourceDetails?: Record<string, unknown>
    connectionConfigId?: string | null
    applicability?: Record<string, unknown>
    availability?: Availability
    priority?: number
  }) => void
  isPending: boolean
}) {
  const [sourceType, setSourceType] = useState<SourceType>('INLINE')
  const [content, setContent] = useState('')
  const [connectionConfigId, setConnectionConfigId] = useState<string>('')
  const [availability, setAvailability] = useState<Availability>('REQUIRED')
  const [priority, setPriority] = useState('100')
  const [conversation, setConversation] = useState(true)
  const [workflow, setWorkflow] = useState(true)

  const { data: connectionConfigs } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
    enabled: sourceType === 'GIT',
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      sourceType,
      sourceDetails: sourceType === 'INLINE' ? { content } : undefined,
      connectionConfigId: sourceType === 'GIT' ? connectionConfigId || undefined : undefined,
      applicability: {
        CONVERSATION: String(conversation),
        WORKFLOW: String(workflow),
      },
      availability,
      priority: parseInt(priority) || 100,
    })
    // Reset
    setSourceType('INLINE')
    setContent('')
    setConnectionConfigId('')
    setAvailability('REQUIRED')
    setPriority('100')
    setConversation(true)
    setWorkflow(true)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Draft Version</DialogTitle>
          <DialogDescription>Configure the source and behaviour for this instruction</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label>Source Type</Label>
            <Select value={sourceType} onValueChange={(v) => setSourceType(v as SourceType)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="INLINE">Inline (text content)</SelectItem>
                <SelectItem value="GIT">Git (from repository)</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {sourceType === 'INLINE' && (
            <div className="space-y-2">
              <Label htmlFor="content">Content</Label>
              <Textarea
                id="content"
                value={content}
                onChange={(e) => setContent(e.target.value)}
                rows={8}
                className="font-mono text-sm"
                placeholder="Enter instruction content…"
                required
              />
            </div>
          )}

          {sourceType === 'GIT' && (
            <div className="space-y-2">
              <Label>Connection Config</Label>
              <Select value={connectionConfigId} onValueChange={setConnectionConfigId}>
                <SelectTrigger><SelectValue placeholder="Select a Git connection" /></SelectTrigger>
                <SelectContent>
                  {(connectionConfigs ?? [])
                    .filter((c) => c.type === 'GIT' && c.status === 'ACTIVE')
                    .map((c) => (
                      <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                    ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="space-y-2">
            <Label>Applicability</Label>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={conversation} onChange={(e) => setConversation(e.target.checked)} />
                CONVERSATION
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={workflow} onChange={(e) => setWorkflow(e.target.checked)} />
                WORKFLOW
              </label>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Availability</Label>
              <Select value={availability} onValueChange={(v) => setAvailability(v as Availability)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="REQUIRED">Required</SelectItem>
                  <SelectItem value="OPTIONAL">Optional</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="priority">Priority</Label>
              <Input
                id="priority"
                type="number"
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
              />
            </div>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending}>
              {isPending ? 'Creating…' : 'Create Draft'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

// --- Preview Dialog ---

function PreviewDialog({
  version,
  onOpenChange,
}: {
  version: InstructionAssetVersion
  onOpenChange: (open: boolean) => void
}) {
  const content = (version.sourceDetails as { content?: string })?.content ?? ''

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Preview — v{version.versionNumber} (Draft)</DialogTitle>
          <DialogDescription>Read-only preview of the draft content</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="flex gap-2">
            <Badge variant="outline">{version.sourceType}</Badge>
            <Badge variant="outline">{version.availability}</Badge>
            <Badge variant="outline">Priority: {version.priority}</Badge>
          </div>
          {version.sourceType === 'INLINE' ? (
            <pre className="text-sm bg-muted/50 rounded-md p-4 whitespace-pre-wrap max-h-96 overflow-y-auto">
              {content || '(empty)'}
            </pre>
          ) : (
            <div className="text-sm text-muted-foreground">
              Git-sourced content. Use Sync to fetch from repository.
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}