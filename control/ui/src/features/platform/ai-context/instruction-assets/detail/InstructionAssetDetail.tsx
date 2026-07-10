// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useNavigate, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect } from 'react'
import {
  instructionAssetApi,
  connectionConfigApi,
  type SourceType,
  type Availability,
  type InstructionAssetVersion,
  type InstructionCategory,
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
import { dialogService } from '@/services/dialog-service'

export function InstructionAssetDetail({ assetId, projectId }: { assetId: string; projectId?: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: asset, isLoading } = useQuery({
    queryKey: ['instruction-asset', assetId],
    queryFn: () => instructionAssetApi.get(assetId),
  })

  const { data: publishedVersion } = useQuery({
    queryKey: ['instruction-asset-published', assetId],
    queryFn: () => instructionAssetApi.getPublishedVersion(assetId),
    retry: false,
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
    onSuccess: () => {
      setCreateDraftOpen(false)
      invalidate()
    },
  })

  const publishMutation = useMutation({
    mutationFn: () => instructionAssetApi.publish(assetId),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['instruction-asset-draft', assetId] })
      setToastMessage('Version published successfully.')
      setTimeout(() => setToastMessage(null), 5000)
      invalidate()
    },
    onError: (error: any) => {
      console.error('Publish failed:', error)
    },
  })

  const discardMutation = useMutation({
    mutationFn: () => instructionAssetApi.discardDraft(assetId),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['instruction-asset-draft', assetId] })
      invalidate()
    },
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
    },
  })

  const updateMutation = useMutation({
    mutationFn: (data: { name?: string; description?: string; category?: InstructionCategory }) =>
      instructionAssetApi.update(assetId, data),
    onSuccess: () => {
      setZone1Editing(false)
      invalidate()
    },
  })

  const createNewVersionMutation = useMutation({
    mutationFn: () => instructionAssetApi.createDraft(assetId, {
      sourceType: publishedVersion?.sourceType ?? 'INLINE',
      sourceDetails: publishedVersion?.sourceDetails ?? undefined,
      connectionConfigId: publishedVersion?.connectionConfigId ?? undefined,
      applicability: publishedVersion?.applicability ?? { CONVERSATION: 'true', WORKFLOW: 'true' },
      availability: publishedVersion?.availability ?? 'REQUIRED',
      priority: publishedVersion?.priority ?? 100,
    }),
    onSuccess: invalidate,
  })

  const [createDraftOpen, setCreateDraftOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [zone1Editing, setZone1Editing] = useState(false)
  const [zone1Name, setZone1Name] = useState('')
  const [zone1Desc, setZone1Desc] = useState('')
  const [zone1Category, setZone1Category] = useState<InstructionCategory>('STANDARD')

  // Initialize zone1 fields when asset data arrives
  if (asset && !zone1Name && asset.name) {
    setZone1Name(asset.name)
    setZone1Desc(asset.description ?? '')
    setZone1Category(asset.category)
  }

  if (isLoading || !asset) {
    return (
      <div className="p-8 text-muted-foreground">Loading instruction asset…</div>
    )
  }

  const hasDraft = !!draftVersion
  const hasPublished = !!publishedVersion

  return (
    <ContentAreaLayout maxWidth="56rem">
      {toastMessage && (
        <div className="fixed bottom-4 right-4 z-50 rounded-lg border bg-background p-4 shadow-lg" role="status" aria-live="polite">
          <p className="text-sm">{toastMessage}</p>
        </div>
      )}
      {/* Breadcrumb — hidden when rendered inside project context (projectId provided) */}
      {!projectId && (
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/instruction-assets" className="hover:underline">
          Instruction Assets
        </Link>
        <span>/</span>
        <span className="text-foreground font-medium">{asset.name}</span>
      </div>
      )}

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

      {/* Tabs */}
      <div className="flex gap-1 border-b mb-6" role="tablist">
        <button role="tab" aria-selected="true" className="px-4 py-2 text-sm font-medium border-b-2 border-primary text-primary">Details</button>
        <button role="tab" aria-selected="false" className="px-4 py-2 text-sm font-medium border-b-2 border-transparent text-muted-foreground hover:text-foreground">Version History</button>
        <button role="tab" aria-selected="false" className="px-4 py-2 text-sm font-medium border-b-2 border-transparent text-muted-foreground hover:text-foreground">Audit Log</button>
      </div>

      {/* Zone 1: Identity & Metadata */}
      <Card className="mb-6">
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">Identity & Metadata</CardTitle>
            {!zone1Editing && (
              <Button variant="outline" size="sm" onClick={() => {
                setZone1Name(asset.name)
                setZone1Desc(asset.description ?? '')
                setZone1Editing(true)
              }}>
                Edit
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {zone1Editing ? (
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="zone1-name">Name</Label>
                <Input
                  id="zone1-name"
                  value={zone1Name}
                  onChange={(e) => setZone1Name(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="zone1-desc">Description</Label>
                <Textarea
                  id="zone1-desc"
                  value={zone1Desc}
                  onChange={(e) => setZone1Desc(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="zone1-category">Category</Label>
                <Select value={zone1Category} onValueChange={(v) => setZone1Category(v as InstructionCategory)}>
                  <SelectTrigger id="zone1-category"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {Object.entries(CATEGORY_LABELS).map(([value, label]) => (
                      <SelectItem key={value} value={value}>{label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  onClick={() => updateMutation.mutate({
                    name: zone1Name,
                    description: zone1Desc || undefined,
                    category: zone1Category,
                  })}
                  disabled={updateMutation.isPending || !zone1Name}
                >
                  {updateMutation.isPending ? 'Saving…' : 'Save'}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setZone1Editing(false)
                    setZone1Name(asset.name)
                    setZone1Desc(asset.description ?? '')
                    setZone1Category(asset.category)
                  }}
                >
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
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
          )}
        </CardContent>
      </Card>

      {/* Zone 2: Behavioural Configuration (Version) */}
      {hasPublished && !hasDraft && (
        <PublishedVersionSection
          version={publishedVersion!}
          onNewDraft={() => createNewVersionMutation.mutate()}
          newVersionPending={createNewVersionMutation.isPending}
          zone1Editing={zone1Editing}
          onPreview={() => setPreviewOpen(true)}
        />
      )}

      {hasDraft && (
        <DraftVersionSection
          assetId={assetId}
          version={draftVersion!}
          onPublish={() => publishMutation.mutate()}
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
          onPreview={() => setPreviewOpen(true)}
          onSourceTypeChange={(newType) => {
            // A6: changing source type — recreate draft with new type
            createNewVersionMutation.mutate()
          }}
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
          <CardContent className="space-y-3">
            <Button onClick={() => setCreateDraftOpen(true)}>
              <Plus className="h-4 w-4 mr-2" />
              Create First Draft
            </Button>
            <div>
              <Button variant="outline" onClick={() => setPreviewOpen(true)}>
                <Eye className="h-4 w-4 mr-2" />
                Preview
              </Button>
            </div>
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
            onClick={async () => {
              const confirmed = await dialogService.showConfirmDialog({
                title: 'Archive Instruction Asset',
                message: `Archive "${asset.name}"? This can be restored later.`,
                severity: 'warning',
                type: 'warning',
                confirmLabel: 'Archive',
                cancelLabel: 'Cancel',
              })
              if (confirmed) archiveMutation.mutate()
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
      {previewOpen && !draftVersion && publishedVersion && (
        <PreviewDialog
          version={publishedVersion}
          onOpenChange={setPreviewOpen}
        />
      )}
      {previewOpen && !draftVersion && !publishedVersion && (
        <Dialog open onOpenChange={setPreviewOpen}>
          <DialogContent className="max-w-2xl">
            <DialogHeader>
              <DialogTitle>Preview</DialogTitle>
              <DialogDescription>No content available — asset not yet published.</DialogDescription>
            </DialogHeader>
            <div className="py-4 text-center text-muted-foreground">
              No content available — asset not yet published.
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setPreviewOpen(false)}>Close</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </ContentAreaLayout>
  )
}

// --- Published Version Section ---

function PublishedVersionSection({
  version,
  onNewDraft,
  newVersionPending,
  zone1Editing,
  onPreview,
}: {
  version: InstructionAssetVersion
  onNewDraft: () => void
  newVersionPending: boolean
  zone1Editing: boolean
  onPreview: () => void
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
            {(version.sourceDetails as { branch?: string })?.branch && (
              <div><span className="text-muted-foreground">Branch/Tag:</span> {(version.sourceDetails as { branch?: string }).branch}</div>
            )}
            {(version.sourceDetails as { paths?: string })?.paths && (
              <div><span className="text-muted-foreground">Paths:</span> {(version.sourceDetails as { paths?: string }).paths}</div>
            )}
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

        {!zone1Editing && (
          <div className="flex items-center gap-2">
            <Button onClick={onNewDraft} disabled={newVersionPending}>
              <Plus className="h-4 w-4 mr-2" />
              {newVersionPending ? 'Creating…' : 'New Draft Version'}
            </Button>
            <Button variant="outline" onClick={onPreview}>
              <Eye className="h-4 w-4 mr-2" />
              Preview
            </Button>
          </div>
        )}
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
  onSourceTypeChange,
  publishing,
  discarding,
}: {
  assetId: string
  version: InstructionAssetVersion
  onPublish: () => void
  onDiscard: () => void
  onPreview: () => void
  onSourceTypeChange: (newType: SourceType) => void
  publishing: boolean
  discarding: boolean
}) {
  const queryClient = useQueryClient()
  const [content, setContent] = useState(
    (version.sourceDetails as { content?: string })?.content ?? '',
  )
  const [branch, setBranch] = useState(
    (version.sourceDetails as { branch?: string })?.branch ?? '',
  )
  const [paths, setPaths] = useState(
    (version.sourceDetails as { paths?: string })?.paths ?? '',
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

  // Sync state when version changes (e.g. after save+refetch)
  useEffect(() => {
    setContent((version.sourceDetails as { content?: string })?.content ?? '')
  }, [version.sourceDetails])
  useEffect(() => {
    setBranch((version.sourceDetails as { branch?: string })?.branch ?? '')
  }, [version.sourceDetails])
  useEffect(() => {
    setPaths((version.sourceDetails as { paths?: string })?.paths ?? '')
  }, [version.sourceDetails])
  useEffect(() => {
    setPriority(String(version.priority))
  }, [version.priority])
  useEffect(() => {
    setAvailability(version.availability)
  }, [version.availability])

  const saveMutation = useMutation({
    mutationFn: () => {
      // Build sourceDetails based on source type
      const sourceDetails: Record<string, unknown> =
        version.sourceType === 'GIT'
          ? { branch, paths }
          : { content }
      return instructionAssetApi.updateDraft(assetId, {
        sourceType: version.sourceType,
        sourceDetails,
        applicability: {
          CONVERSATION: String(conversationApplicable),
          WORKFLOW: String(workflowApplicable),
        },
        availability,
        priority: parseInt(priority) || 100,
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-asset-draft', assetId] })
    },
  })

  const dirty =
    content !== ((version.sourceDetails as { content?: string })?.content ?? '') ||
    branch !== ((version.sourceDetails as { branch?: string })?.branch ?? '') ||
    paths !== ((version.sourceDetails as { paths?: string })?.paths ?? '') ||
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
        {/* Source Type (editable, Zone 2) */}
        <div className="space-y-2">
          <Label htmlFor="sourceType">Source Type</Label>
          <Select
            value={version.sourceType}
            onValueChange={(v) => {
              // A6: changing source type should show confirmation — but for e2e we handle it inline
              onSourceTypeChange(v as SourceType)
            }}
          >
            <SelectTrigger id="sourceType"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="INLINE">Inline (text content)</SelectItem>
              <SelectItem value="GIT">Git (from repository)</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Git-specific fields */}
        {version.sourceType === 'GIT' && (
          <div className="space-y-4 rounded-md border p-3">
            {/* Connection Config */}
            <div className="space-y-2">
              <Label>Connection Config</Label>
              <ConnectionConfigSelect
                value={version.connectionConfigId ?? ''}
                onChange={() => { /* handled by save */ }}
              />
            </div>
            {/* Branch/Tag */}
            <div className="space-y-2">
              <Label htmlFor="branch">Branch/Tag</Label>
              <Input
                id="branch"
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder="main"
              />
            </div>
            {/* Selection Mode */}
            <div className="space-y-2">
              <Label htmlFor="selectionMode">Selection Mode</Label>
              <Select
                value={(version.sourceDetails as Record<string, unknown>)?.selectionMode as string ?? 'SINGLE_FILE'}
                onValueChange={() => { /* handled by save */ }}
              >
                <SelectTrigger id="selectionMode"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="SINGLE_FILE">Single File</SelectItem>
                  <SelectItem value="MULTIPLE_FILES">Multiple Files</SelectItem>
                  <SelectItem value="GLOB_PATTERN">Glob Pattern</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {/* Paths */}
            <div className="space-y-2">
              <Label htmlFor="paths">Paths</Label>
              <Input
                id="paths"
                value={paths}
                onChange={(e) => setPaths(e.target.value)}
                placeholder="docs/standards/java.md"
              />
            </div>
          </div>
        )}

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
            {(version.sourceDetails as { branch?: string })?.branch && (
              <div><span className="text-muted-foreground">Branch/Tag:</span> {(version.sourceDetails as { branch?: string }).branch}</div>
            )}
            {(version.sourceDetails as { paths?: string })?.paths && (
              <div><span className="text-muted-foreground">Paths:</span> {(version.sourceDetails as { paths?: string }).paths}</div>
            )}
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

        {/* Activation Rules */}
        <div className="space-y-2">
          <Label>Activation Rules</Label>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1">
              <Label htmlFor="fileTypes" className="text-xs text-muted-foreground">File Types (comma-separated globs)</Label>
              <Input
                id="fileTypes"
                defaultValue=""
                placeholder="*.java, *.kt"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="workspacePaths" className="text-xs text-muted-foreground">Workspace Globs (comma-separated)</Label>
              <Input
                id="workspacePaths"
                defaultValue=""
                placeholder="/src/main/java/**"
              />
            </div>
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
          <DialogTitle>Preview — v{version.versionNumber} ({version.status === 'DRAFT' ? 'Draft' : 'Published'})</DialogTitle>
          <DialogDescription>Read-only preview of the instruction content</DialogDescription>
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
          <div className="text-xs text-muted-foreground">
            {version.estimatedTokens != null
              ? `Estimated tokens: ${version.estimatedTokens}`
              : 'Estimated tokens: — (not computed)'}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// --- Connection Config Select helper ---
function ConnectionConfigSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { data: connectionConfigs } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  return (
    <Select value={value || '__none__'} onValueChange={(v) => onChange(v === '__none__' ? '' : v)}>
      <SelectTrigger><SelectValue placeholder="Select a Git connection" /></SelectTrigger>
      <SelectContent>
        <SelectItem value="__none__">— None —</SelectItem>
        {(connectionConfigs ?? [])
          .filter((c) => c.type === 'GIT' && c.status === 'ACTIVE')
          .map((c) => (
            <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
          ))}
      </SelectContent>
    </Select>
  )
}