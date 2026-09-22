// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useEffect, useMemo } from 'react'
import {
  agentProfilesApi,
  modelsApi,
  toolsApi,
  ApiRequestError,
  type AgentProfile,
  type AgentProfileVersion,
  type Model,
  type Tool,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { MultiSelect, type MultiSelectOption } from '@/components/ui/multi-select'
import { ContentAreaLayout } from '@/components/content-area-layout'
import {
  Plus,
  Save,
  Trash2,
  Power,
  PowerOff,
  Cpu,
  Wrench,
  Bot,
} from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import { RequiredMark } from '@/components/ui/required-marks'
import { StatusBadge } from '../shared/StatusBadge'

const TAB_LABELS = ['Details', 'Version History', 'Audit Log'] as const

export function AgentProfileDetail({ profileId }: { profileId: string }) {
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState<(typeof TAB_LABELS)[number]>('Details')

  const { data: profile, isLoading } = useQuery({
    queryKey: ['agent-profile', profileId],
    queryFn: () => agentProfilesApi.get(profileId),
  })

  // The published version supplies the Zone 2 behaviour content shown when
  // no draft is open. 404 (no published version) means "none" - retry: false.
  const { data: publishedVersion } = useQuery({
    queryKey: ['agent-profile-published', profileId],
    queryFn: () => agentProfilesApi.listVersions(profileId),
    enabled: !!profile?.publishedVersionId,
    select: (versions) =>
      versions.find((v) => v.id === profile?.publishedVersionId) ?? null,
  })

  // The open draft. 404 means no draft - retry: false (ConnectionDetail).
  const { data: draftVersion } = useQuery({
    queryKey: ['agent-profile-draft', profileId],
    queryFn: () => agentProfilesApi.getDraft(profileId),
    retry: false,
  })

  const [identityEditing, setIdentityEditing] = useState(false)
  const [identityName, setIdentityName] = useState('')
  const [identityDesc, setIdentityDesc] = useState('')
  const [publishError, setPublishError] = useState<string | null>(null)
  const [openDraftError, setOpenDraftError] = useState<string | null>(null)

  // Initialize identity fields when the profile loads.
  if (profile && !identityName && profile.name) {
    setIdentityName(profile.name)
    setIdentityDesc(profile.description ?? '')
  }

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['agent-profile', profileId] })
    queryClient.invalidateQueries({ queryKey: ['agent-profile-published', profileId] })
    queryClient.invalidateQueries({ queryKey: ['agent-profile-draft', profileId] })
    queryClient.invalidateQueries({ queryKey: ['agent-profile-versions', profileId] })
    queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
  }

  const updateIdentityMutation = useMutation({
    // Identity-only PUT (engine 2.2): name/description only, no version churn.
    mutationFn: (data: { name: string; description?: string }) =>
      agentProfilesApi.update(profileId, data),
    onSuccess: () => {
      setIdentityEditing(false)
      invalidate()
    },
  })

  const openDraftMutation = useMutation({
    mutationFn: () => agentProfilesApi.openDraft(profileId),
    onSuccess: () => {
      setOpenDraftError(null)
      invalidate()
    },
    onError: (error) => setOpenDraftError(describeError(error, 'Failed to open a draft version')),
  })

  const publishMutation = useMutation({
    mutationFn: () => agentProfilesApi.publish(profileId),
    onSuccess: () => {
      setIdentityEditing(false)
      // Drop the cached draft so the Published card renders immediately.
      queryClient.removeQueries({ queryKey: ['agent-profile-draft', profileId] })
      setPublishError(null)
      invalidate()
    },
    onError: (error) => setPublishError(describeError(error, 'Publish failed')),
  })

  const discardMutation = useMutation({
    mutationFn: () => agentProfilesApi.discardDraft(profileId),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['agent-profile-draft', profileId] })
      invalidate()
    },
  })

  const activateMutation = useMutation({
    mutationFn: () => agentProfilesApi.activate(profileId),
    onSuccess: invalidate,
  })

  const deactivateMutation = useMutation({
    mutationFn: () => agentProfilesApi.deactivate(profileId),
    onSuccess: invalidate,
  })

  const deleteMutation = useMutation({
    mutationFn: () => agentProfilesApi.delete(profileId),
    onSuccess: invalidate,
  })

  if (isLoading || !profile) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading agent profile...</div>
      </div>
    )
  }

  const hasDraft = !!draftVersion
  const hasPublished = !!profile.publishedVersionId

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-infra/agent-profiles" className="hover:underline">
          Agent Profiles
        </Link>
        <span>/</span>
        <span className="text-foreground font-medium">{profile.name}</span>
      </div>

      <div className="flex items-start justify-between mb-6">
        <div className="flex items-start gap-3">
          <Bot className="h-6 w-6 text-muted-foreground mt-1" />
          <div>
            <h1 className="text-2xl font-bold">{profile.name}</h1>
            {profile.description && (
              <p className="text-muted-foreground">{profile.description}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          {profile.publishedVersionNumber != null ? (
            <Badge>Published v{profile.publishedVersionNumber}</Badge>
          ) : (
            <Badge variant="secondary">Unpublished</Badge>
          )}
          {profile.draftVersionId && (
            <Badge
              variant="secondary"
              className="bg-amber-100 text-amber-900 hover:bg-amber-100"
              title={`Draft v${profile.draftVersionNumber} is open - publish to make it live, or discard it`}
            >
              Draft open
            </Badge>
          )}
          <StatusBadge status={profile.status} />
        </div>
      </div>

      {/* Tab strip - Details and Version History toggle; Audit Log is an inert label */}
      <div className="flex gap-1 border-b mb-6" role="tablist">
        {TAB_LABELS.map((label) => {
          const active = label === activeTab
          const clickable = label !== 'Audit Log'
          return (
            <button
              key={label}
              role="tab"
              aria-selected={active}
              onClick={clickable ? () => setActiveTab(label) : undefined}
              className={
                active
                  ? 'px-4 py-2 text-sm font-medium border-b-2 border-primary text-primary'
                  : 'px-4 py-2 text-sm font-medium border-b-2 border-transparent text-muted-foreground hover:text-foreground'
              }
            >
              {label}
            </button>
          )
        })}
      </div>

      {activeTab === 'Details' ? (
        <>
          {/* Zone 1: Identity (view/edit toggle, identity-only save) */}
          <Card className="mb-6">
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-base">Identity &amp; Metadata</CardTitle>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    if (identityEditing) {
                      setIdentityName(profile.name)
                      setIdentityDesc(profile.description ?? '')
                      setIdentityEditing(false)
                    } else {
                      setIdentityEditing(true)
                    }
                  }}
                >
                  {identityEditing ? 'Cancel' : 'Edit'}
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              {identityEditing ? (
                <div className="space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="identity-name">
                      Name<RequiredMark />
                    </Label>
                    <Input
                      id="identity-name"
                      value={identityName}
                      onChange={(e) => setIdentityName(e.target.value)}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="identity-desc">Description</Label>
                    <Input
                      id="identity-desc"
                      value={identityDesc}
                      onChange={(e) => setIdentityDesc(e.target.value)}
                      placeholder="Brief description"
                    />
                  </div>
                  {updateIdentityMutation.error && (
                    <div className="text-destructive text-sm">
                      {updateIdentityMutation.error.message}
                    </div>
                  )}
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      onClick={() =>
                        updateIdentityMutation.mutate({
                          name: identityName,
                          description: identityDesc || undefined,
                        })
                      }
                      disabled={updateIdentityMutation.isPending || identityName.trim() === ''}
                    >
                      {updateIdentityMutation.isPending ? 'Saving...' : 'Save'}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setIdentityName(profile.name)
                        setIdentityDesc(profile.description ?? '')
                        setIdentityEditing(false)
                      }}
                    >
                      Cancel
                    </Button>
                  </div>
                </div>
              ) : (
                <dl className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <dt className="text-muted-foreground">Name</dt>
                    <dd>{profile.name}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Status</dt>
                    <dd>
                      <StatusBadge status={profile.status} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Description</dt>
                    <dd>{profile.description || '-'}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">Created</dt>
                    <dd>{new Date(profile.createdAt).toLocaleString()}</dd>
                  </div>
                  {profile.updatedAt && (
                    <div>
                      <dt className="text-muted-foreground">Updated</dt>
                      <dd>{new Date(profile.updatedAt).toLocaleString()}</dd>
                    </div>
                  )}
                </dl>
              )}
            </CardContent>
          </Card>

          {/* Zone 2: Version - three states */}
          {hasPublished && !hasDraft && (
            <PublishedVersionCard
              profile={profile}
              published={publishedVersion ?? null}
              onOpenDraft={() => {
                setOpenDraftError(null)
                openDraftMutation.mutate()
              }}
              opening={openDraftMutation.isPending}
              openDraftError={openDraftError}
            />
          )}

          {hasDraft && (
            <DraftSection
              profileId={profileId}
              profileName={profile.name}
              draft={draftVersion!}
              hasPublished={hasPublished}
              onPublish={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Publish Agent Profile',
                  message: `Publish draft v${draftVersion!.versionNumber} of "${profile.name}"? The draft becomes the live published version immediately; the previous published version is archived.`,
                  severity: 'warning',
                  type: 'warning',
                  confirmLabel: 'Publish',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) {
                  setPublishError(null)
                  publishMutation.mutate()
                }
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
              publishError={publishError}
            />
          )}

          {!hasPublished && !hasDraft && (
            <Card className="mb-6">
              <CardHeader>
                <CardTitle className="text-base">No Version Yet</CardTitle>
                <CardDescription>
                  Create a draft to configure the behaviour contract and publish this profile.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Button
                  onClick={() => {
                    setOpenDraftError(null)
                    openDraftMutation.mutate()
                  }}
                  disabled={openDraftMutation.isPending}
                >
                  <Plus className="h-4 w-4 mr-2" /> Create First Draft
                </Button>
                {openDraftError && (
                  <div className="text-destructive text-sm mt-3">{openDraftError}</div>
                )}
              </CardContent>
            </Card>
          )}

          {/* Lifecycle actions row (operate on the profile, not versions) */}
          <div className="flex items-center gap-2 mt-6">
            {profile.status === 'ACTIVE' ? (
              <Button
                variant="outline"
                onClick={() => deactivateMutation.mutate()}
                disabled={deactivateMutation.isPending}
              >
                <PowerOff className="h-4 w-4 mr-2" /> Deactivate
              </Button>
            ) : (
              <Button
                variant="outline"
                onClick={() => activateMutation.mutate()}
                disabled={activateMutation.isPending}
              >
                <Power className="h-4 w-4 mr-2" /> Activate
              </Button>
            )}
            <Button
              variant="ghost"
              className="text-destructive"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Agent Profile',
                  message: `Delete "${profile.name}"? This cannot be undone.`,
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate()
              }}
              disabled={deleteMutation.isPending}
            >
              <Trash2 className="h-4 w-4 mr-2" /> Delete
            </Button>
          </div>
        </>
      ) : (
        <VersionHistoryTable profileId={profileId} />
      )}
    </ContentAreaLayout>
  )
}

/** Zone 2 card: the live published version (shown while no draft is open). */
function PublishedVersionCard({
  profile,
  published,
  onOpenDraft,
  opening,
  openDraftError,
}: {
  profile: AgentProfile
  published: AgentProfileVersion | null
  onOpenDraft: () => void
  opening: boolean
  openDraftError: string | null
}) {
  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">
              Published Version (v{profile.publishedVersionNumber})
            </CardTitle>
            <CardDescription>
              {published?.publishedAt ? new Date(published.publishedAt).toLocaleString() : '-'}
            </CardDescription>
          </div>
          <Badge>Published</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-muted-foreground">Default Model</dt>
            <dd>
              {published?.defaultModel || profile.defaultModel ? (
                <Badge variant="secondary" className="text-xs font-mono">
                  {published?.defaultModel ?? profile.defaultModel}
                </Badge>
              ) : (
                '-'
              )}
            </dd>
          </div>
        </div>
        <div>
          <dt className="text-muted-foreground mb-2 text-sm">Capabilities</dt>
          <div className="flex flex-wrap gap-1">
            {(published?.capabilities ?? []).map((cap) => (
              <Badge key={cap} variant="secondary" className="text-xs">
                <Cpu className="h-3 w-3 mr-1" />
                {cap}
              </Badge>
            ))}
            {!(published?.capabilities ?? []).length && (
              <span className="text-muted-foreground text-sm">-</span>
            )}
          </div>
        </div>
        <div>
          <dt className="text-muted-foreground mb-2 text-sm">Tools</dt>
          <div className="flex flex-wrap gap-1">
            {(published?.toolCodes ?? []).map((tool) => (
              <Badge key={tool} variant="outline" className="text-xs">
                <Wrench className="h-3 w-3 mr-1" />
                {tool}
              </Badge>
            ))}
            {!(published?.toolCodes ?? []).length && (
              <span className="text-muted-foreground text-sm">-</span>
            )}
          </div>
        </div>
        {(published?.systemPrompt ?? null) != null && (
          <div>
            <Label className="mb-2 block">System Prompt</Label>
            <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
              {published!.systemPrompt}
            </pre>
          </div>
        )}
        <Button onClick={onOpenDraft} disabled={opening}>
          <Plus className="h-4 w-4 mr-2" /> {opening ? 'Opening...' : 'New Draft Version'}
        </Button>
        {openDraftError && <div className="text-destructive text-sm">{openDraftError}</div>}
      </CardContent>
    </Card>
  )
}

/** Zone 2 draft editor: behaviour fields, save, publish, discard. */
function DraftSection({
  profileId,
  profileName,
  draft,
  hasPublished,
  onPublish,
  onDiscard,
  publishing,
  discarding,
  publishError,
}: {
  profileId: string
  profileName: string
  draft: AgentProfileVersion
  hasPublished: boolean
  onPublish: () => void
  onDiscard: () => void
  publishing: boolean
  discarding: boolean
  publishError: string | null
}) {
  const queryClient = useQueryClient()
  const [capabilities, setCapabilities] = useState<string>(
    (draft.capabilities ?? []).join('\n'),
  )
  const [toolCodes, setToolCodes] = useState<string[]>(draft.toolCodes ?? [])
  const [systemPrompt, setSystemPrompt] = useState<string>(draft.systemPrompt ?? '')
  const [defaultModel, setDefaultModel] = useState<string>(draft.defaultModel ?? '')
  const [saveError, setSaveError] = useState<string | null>(null)

  // Re-seed local state when the draft version changes (after save/refetch).
  useEffect(() => {
    setCapabilities((draft.capabilities ?? []).join('\n'))
    setToolCodes(draft.toolCodes ?? [])
    setSystemPrompt(draft.systemPrompt ?? '')
    setDefaultModel(draft.defaultModel ?? '')
  }, [draft.id, draft.capabilities, draft.toolCodes, draft.systemPrompt, draft.defaultModel])

  const { data: models } = useQuery({
    queryKey: ['models'],
    queryFn: () => modelsApi.list(),
  })

  const { data: tools } = useQuery({
    queryKey: ['tools'],
    queryFn: () => toolsApi.list(),
  })

  const toolOptions: MultiSelectOption[] = useMemo(
    () =>
      (tools ?? [])
        .filter((t: Tool) => t.status === 'ACTIVE')
        .map((t: Tool) => ({
          value: t.code,
          label: t.name,
          description: t.toolType,
        })),
    [tools],
  )

  const saveDraftMutation = useMutation({
    mutationFn: () =>
      agentProfilesApi.updateDraft(profileId, {
        capabilities: capabilities
          .split('\n')
          .map((s) => s.trim())
          .filter(Boolean),
        toolCodes,
        systemPrompt: systemPrompt.trim() || undefined,
        defaultModel: defaultModel || undefined,
      }),
    onSuccess: () => {
      setSaveError(null)
      queryClient.invalidateQueries({ queryKey: ['agent-profile-draft', profileId] })
    },
    onError: (error) => setSaveError(describeError(error, 'Failed to save the draft')),
  })

  return (
    <Card className="mb-6 border-primary/30">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Draft (v{draft.versionNumber})</CardTitle>
            <CardDescription>Edit and publish this draft</CardDescription>
          </div>
          <Badge variant="secondary">Draft</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="draft-default-model">Default Model</Label>
          <Select value={defaultModel} onValueChange={setDefaultModel}>
            <SelectTrigger id="draft-default-model">
              <SelectValue placeholder="Select a model" />
            </SelectTrigger>
            <SelectContent>
              {(models ?? [])
                .filter((m: Model) => m.status === 'ACTIVE')
                .map((model: Model) => (
                  <SelectItem key={model.code} value={model.code}>
                    {model.name} ({model.code})
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            The LLM model that agents with this profile use for task execution.
          </p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="draft-system-prompt">
            System Prompt
            <span className="text-muted-foreground text-xs ml-2">(expertise/personality)</span>
          </Label>
          <Textarea
            id="draft-system-prompt"
            value={systemPrompt}
            onChange={(e) => setSystemPrompt(e.target.value)}
            rows={4}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="draft-capabilities">
            Capabilities
            <span className="text-muted-foreground text-xs ml-2">(one per line)</span>
          </Label>
          <Textarea
            id="draft-capabilities"
            value={capabilities}
            onChange={(e) => setCapabilities(e.target.value)}
            rows={4}
            className="font-mono text-sm"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="draft-tools">
            Tools
            <span className="text-muted-foreground text-xs ml-2">(from the tools registry)</span>
          </Label>
          <MultiSelect
            options={toolOptions}
            selected={toolCodes}
            onChange={setToolCodes}
            placeholder="Select tools..."
            searchPlaceholder="Search tools by name or code..."
          />
        </div>

        {saveError && <div className="text-destructive text-sm">{saveError}</div>}

        <div className="flex items-center gap-2">
          <Button
            size="sm"
            onClick={() => saveDraftMutation.mutate()}
            disabled={saveDraftMutation.isPending}
          >
            <Save className="h-4 w-4 mr-2" />
            {saveDraftMutation.isPending ? 'Saving...' : 'Save Draft'}
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-green-600 hover:text-green-700"
            onClick={onPublish}
            disabled={publishing}
          >
            {publishing ? 'Publishing...' : 'Publish'}
          </Button>
          {/* A v1 draft with nothing published must not be discardable -
              discarding would leave the profile with no version at all. */}
          {hasPublished && (
            <Button
              size="sm"
              variant="outline"
              className="text-destructive hover:text-destructive"
              onClick={onDiscard}
              disabled={discarding}
            >
              Discard
            </Button>
          )}
        </div>

        {publishError && (
          <div className="text-destructive text-sm" role="alert">
            {publishError}
          </div>
        )}
        {!publishError && !hasPublished && (
          <p className="text-xs text-muted-foreground">
            Publishing creates the first published version of "{profileName}".
          </p>
        )}
      </CardContent>
    </Card>
  )
}

/** Read-only version history table (Version History tab). */
function VersionHistoryTable({ profileId }: { profileId: string }) {
  const { data: versions, isLoading } = useQuery({
    queryKey: ['agent-profile-versions', profileId],
    queryFn: () => agentProfilesApi.listVersions(profileId),
  })

  if (isLoading) {
    return <div className="p-8 text-muted-foreground">Loading versions...</div>
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Version History</CardTitle>
        <CardDescription>All versions of this agent profile</CardDescription>
      </CardHeader>
      <CardContent>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-muted-foreground">
              <th className="py-2 pr-4 font-medium">Version</th>
              <th className="py-2 pr-4 font-medium">Status</th>
              <th className="py-2 pr-4 font-medium">Created</th>
              <th className="py-2 font-medium">Published</th>
            </tr>
          </thead>
          <tbody>
            {(versions ?? [])
              .slice()
              .sort((a, b) => b.versionNumber - a.versionNumber)
              .map((version) => (
                <tr key={version.id} className="border-b last:border-0">
                  <td className="py-2 pr-4 font-mono text-sm">v{version.versionNumber}</td>
                  <td className="py-2 pr-4">
                    <VersionStatusBadge status={version.status} />
                  </td>
                  <td className="py-2 pr-4 text-sm text-muted-foreground">
                    {new Date(version.createdAt).toLocaleString()}
                  </td>
                  <td className="py-2 text-sm text-muted-foreground">
                    {version.publishedAt ? new Date(version.publishedAt).toLocaleString() : '-'}
                  </td>
                </tr>
              ))}
            {!versions?.length && (
              <tr>
                <td colSpan={4} className="py-4 text-sm text-muted-foreground">
                  No versions yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </CardContent>
    </Card>
  )
}

function VersionStatusBadge({ status }: { status: AgentProfileVersion['status'] }) {
  if (status === 'PUBLISHED') return <Badge>Published</Badge>
  if (status === 'DRAFT')
    return (
      <Badge variant="secondary" className="bg-amber-100 text-amber-900 hover:bg-amber-100">
        Draft
      </Badge>
    )
  return <Badge variant="outline">Archived</Badge>
}

/** Extract a human-readable message from an API error. */
function describeError(error: unknown, fallback: string): string {
  if (error instanceof ApiRequestError && error.error?.message) {
    return error.error.message
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}