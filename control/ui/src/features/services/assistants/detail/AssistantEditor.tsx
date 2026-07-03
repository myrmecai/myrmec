// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  assistantsApi,
  agentProfilesApi,
  type Assistant,
  type AssistantVersion,
  type UpdateAssistantDraftRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { ArrowLeft, Save, Send, Trash2 } from 'lucide-react'

type ReachOption = 'WEB_UI' | 'EXTERNAL_API'
const REACH_OPTIONS: ReachOption[] = ['WEB_UI', 'EXTERNAL_API']

export function AssistantEditor({ assistantId }: { assistantId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: assistant, isLoading: assistantLoading } = useQuery({
    queryKey: ['assistant', assistantId],
    queryFn: () => assistantsApi.get(assistantId),
  })

  const { data: versions } = useQuery({
    queryKey: ['assistant-versions', assistantId],
    queryFn: () => assistantsApi.listVersions(assistantId),
  })

  const draft = versions?.find((v) => v.status === 'DRAFT') ?? null
  const published =
    versions?.find((v) => v.id === assistant?.currentVersionId) ?? null

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['assistant', assistantId] })
    queryClient.invalidateQueries({ queryKey: ['assistant-versions', assistantId] })
  }

  const openDraftMutation = useMutation({
    mutationFn: () => assistantsApi.openDraft(assistantId),
    onSuccess: invalidate,
  })

  const discardDraftMutation = useMutation({
    mutationFn: () => assistantsApi.discardDraft(assistantId),
    onSuccess: invalidate,
  })

  const publishMutation = useMutation({
    mutationFn: () => assistantsApi.publish(assistantId),
    onSuccess: invalidate,
  })

  if (assistantLoading || !assistant) {
    return (
      <div className="p-8 text-muted-foreground">Loading assistant…</div>
    )
  }

  return (
    <div className="p-8 max-w-3xl">
      <Button
        variant="ghost"
        size="sm"
        className="mb-4"
        onClick={() =>
          navigate({
            to: '/assistants',
            search: { projectId: assistant.projectId },
          })
        }
      >
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back to assistants
      </Button>

      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-3xl font-bold">{assistant.name}</h1>
          {assistant.description && (
            <p className="text-muted-foreground">{assistant.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          {assistant.archivedAt && <Badge variant="outline">ARCHIVED</Badge>}
          {assistant.disabled && <Badge variant="outline">DISABLED</Badge>}
          {published ? (
            <Badge>Published v{published.versionNumber}</Badge>
          ) : (
            <Badge variant="secondary">Unpublished</Badge>
          )}
          {draft && <Badge variant="secondary">Draft open</Badge>}
        </div>
      </div>

      <ParentSection assistant={assistant} onSaved={invalidate} />

      {draft ? (
        <DraftSection
          assistantId={assistantId}
          draft={draft}
          onSaved={invalidate}
          onPublish={() => publishMutation.mutate()}
          onDiscard={() => {
            if (confirm('Discard this draft? Unpublished changes are lost.')) {
              discardDraftMutation.mutate()
            }
          }}
          publishing={publishMutation.isPending}
          publishError={publishMutation.error?.message}
          discarding={discardDraftMutation.isPending}
        />
      ) : (
        <PublishedSection
          published={published}
          onOpenDraft={() => openDraftMutation.mutate()}
          opening={openDraftMutation.isPending}
        />
      )}
    </div>
  )
}

function ParentSection({
  assistant,
  onSaved,
}: {
  assistant: Assistant
  onSaved: () => void
}) {
  const [name, setName] = useState(assistant.name)
  const [description, setDescription] = useState(assistant.description ?? '')

  const updateMutation = useMutation({
    mutationFn: () =>
      assistantsApi.update(assistant.id, {
        name: name.trim(),
        description: description.trim(),
      }),
    onSuccess: onSaved,
  })

  const dirty =
    name.trim() !== assistant.name ||
    description.trim() !== (assistant.description ?? '')

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>Details</CardTitle>
        <CardDescription>
          Name and description apply immediately — no version bump.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="a-name">Name</Label>
          <Input
            id="a-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={120}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="a-desc">Description</Label>
          <Textarea
            id="a-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={4000}
            rows={2}
          />
        </div>
        {updateMutation.error && (
          <p className="text-sm text-destructive">
            {updateMutation.error.message}
          </p>
        )}
        <Button
          size="sm"
          disabled={!dirty || name.trim().length === 0 || updateMutation.isPending}
          onClick={() => updateMutation.mutate()}
        >
          <Save className="h-4 w-4 mr-2" />
          Save details
        </Button>
      </CardContent>
    </Card>
  )
}

function PublishedSection({
  published,
  onOpenDraft,
  opening,
}: {
  published: AssistantVersion | null
  onOpenDraft: () => void
  opening: boolean
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Behaviour</CardTitle>
        <CardDescription>
          {published
            ? `Published version v${published.versionNumber}. Open a draft to make changes.`
            : 'Not yet published. Open a draft to configure and publish.'}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {published && (
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <dt className="text-muted-foreground">Greeting</dt>
              <dd>{published.greetingMessage || '—'}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Reach</dt>
              <dd>{published.usableVia?.join(', ') || '—'}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">HITL override</dt>
              <dd>{published.hitlOverrideMode || 'INHERIT'}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Max idle (min)</dt>
              <dd>{published.maxIdleMinutes}</dd>
            </div>
          </dl>
        )}
        <Button size="sm" onClick={onOpenDraft} disabled={opening}>
          {opening ? 'Opening…' : published ? 'Edit (open draft)' : 'Open draft'}
        </Button>
      </CardContent>
    </Card>
  )
}

function DraftSection({
  assistantId,
  draft,
  onSaved,
  onPublish,
  onDiscard,
  publishing,
  publishError,
  discarding,
}: {
  assistantId: string
  draft: AssistantVersion
  onSaved: () => void
  onPublish: () => void
  onDiscard: () => void
  publishing: boolean
  publishError?: string
  discarding: boolean
}) {
  const [agentProfileId, setAgentProfileId] = useState(draft.agentProfileId ?? '')
  const [greeting, setGreeting] = useState(draft.greetingMessage ?? '')
  const [addendum, setAddendum] = useState(draft.addendum ?? '')
  const [hitlMode, setHitlMode] = useState(draft.hitlOverrideMode ?? 'INHERIT')
  const [maxIdle, setMaxIdle] = useState(String(draft.maxIdleMinutes ?? 30))
  const [maxAge, setMaxAge] = useState(
    draft.maxSessionAgeHours != null ? String(draft.maxSessionAgeHours) : '',
  )
  const [reach, setReach] = useState<string[]>(draft.usableVia ?? ['WEB_UI'])
  const [disabledTools, setDisabledTools] = useState<string[]>(
    draft.disabledTools ?? [],
  )
  const [kbBindings, setKbBindings] = useState<string>(
    (draft.kbBindings ?? []).join(', '),
  )

  // Re-seed local state if the draft identity changes (e.g. after takeover).
  useEffect(() => {
    setAgentProfileId(draft.agentProfileId ?? '')
    setGreeting(draft.greetingMessage ?? '')
    setAddendum(draft.addendum ?? '')
    setHitlMode(draft.hitlOverrideMode ?? 'INHERIT')
    setMaxIdle(String(draft.maxIdleMinutes ?? 30))
    setMaxAge(draft.maxSessionAgeHours != null ? String(draft.maxSessionAgeHours) : '')
    setReach(draft.usableVia ?? ['WEB_UI'])
    setDisabledTools(draft.disabledTools ?? [])
    setKbBindings((draft.kbBindings ?? []).join(', '))
  }, [draft.id])

  const { data: profiles } = useQuery({
    queryKey: ['agent-profiles', 'active'],
    queryFn: () => agentProfilesApi.list(true),
  })

  const pinnedProfile = profiles?.find((p) => p.id === agentProfileId)
  const availableTools = pinnedProfile?.toolCodes ?? []

  const saveMutation = useMutation({
    mutationFn: () => {
      const body: UpdateAssistantDraftRequest = {
        agentProfileId: agentProfileId || undefined,
        greetingMessage: greeting,
        addendum,
        hitlOverrideMode: hitlMode,
        maxIdleMinutes: Number.parseInt(maxIdle, 10) || 0,
        maxSessionAgeHours: maxAge.trim() ? Number.parseInt(maxAge, 10) : undefined,
        usableVia: reach,
        disabledTools,
        kbBindings: kbBindings
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
      }
      return assistantsApi.updateDraft(assistantId, body)
    },
    onSuccess: onSaved,
  })

  const toggleReach = (opt: string) =>
    setReach((prev) =>
      prev.includes(opt) ? prev.filter((r) => r !== opt) : [...prev, opt],
    )

  const toggleTool = (code: string) =>
    setDisabledTools((prev) =>
      prev.includes(code) ? prev.filter((t) => t !== code) : [...prev, code],
    )

  return (
    <Card>
      <CardHeader>
        <CardTitle>Draft behaviour</CardTitle>
        <CardDescription>
          Edit the open draft, then publish to make it live.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="space-y-2">
          <Label htmlFor="d-profile">Agent profile (brain)</Label>
          <Select value={agentProfileId} onValueChange={setAgentProfileId}>
            <SelectTrigger id="d-profile">
              <SelectValue placeholder="Select an agent profile" />
            </SelectTrigger>
            <SelectContent>
              {profiles?.map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="d-greeting">Greeting message</Label>
          <Textarea
            id="d-greeting"
            value={greeting}
            onChange={(e) => setGreeting(e.target.value)}
            maxLength={8000}
            rows={2}
            placeholder="Shown once when a conversation starts"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="d-addendum">System prompt addendum</Label>
          <Textarea
            id="d-addendum"
            value={addendum}
            onChange={(e) => setAddendum(e.target.value)}
            maxLength={8000}
            rows={4}
            placeholder="Extra instructions layered on top of the profile's prompt"
          />
        </div>

        <div className="space-y-2">
          <Label>Reach</Label>
          <div className="flex gap-4">
            {REACH_OPTIONS.map((opt) => (
              <label key={opt} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={reach.includes(opt)}
                  onChange={() => toggleReach(opt)}
                />
                {opt}
              </label>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="d-hitl">HITL override</Label>
            <Select
              value={hitlMode}
              onValueChange={(v) => setHitlMode(v as 'INHERIT' | 'STRICT')}
            >
              <SelectTrigger id="d-hitl">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="INHERIT">INHERIT</SelectItem>
                <SelectItem value="STRICT">STRICT</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="d-idle">Max idle (minutes)</Label>
            <Input
              id="d-idle"
              type="number"
              min={1}
              value={maxIdle}
              onChange={(e) => setMaxIdle(e.target.value)}
            />
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="d-age">Max session age (hours, optional)</Label>
          <Input
            id="d-age"
            type="number"
            min={1}
            value={maxAge}
            onChange={(e) => setMaxAge(e.target.value)}
            placeholder="No hard cap"
          />
        </div>

        <div className="space-y-2">
          <Label>Disabled tools</Label>
          {availableTools.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              {agentProfileId
                ? 'The pinned profile exposes no tools.'
                : 'Pin an agent profile to choose tools to disable.'}
            </p>
          ) : (
            <div className="flex flex-wrap gap-3">
              {availableTools.map((code) => (
                <label key={code} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={disabledTools.includes(code)}
                    onChange={() => toggleTool(code)}
                  />
                  {code}
                </label>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="d-kb">Knowledge base bindings (IDs)</Label>
          <Input
            id="d-kb"
            value={kbBindings}
            onChange={(e) => setKbBindings(e.target.value)}
            placeholder="Comma-separated knowledge base IDs"
          />
          <p className="text-xs text-muted-foreground">
            Advanced — paste knowledge base IDs until the KB picker lands.
          </p>
        </div>

        {saveMutation.error && (
          <p className="text-sm text-destructive">{saveMutation.error.message}</p>
        )}
        {publishError && (
          <p className="text-sm text-destructive">{publishError}</p>
        )}

        <div className="flex items-center gap-2 pt-2">
          <Button
            size="sm"
            variant="outline"
            disabled={saveMutation.isPending}
            onClick={() => saveMutation.mutate()}
          >
            <Save className="h-4 w-4 mr-2" />
            Save draft
          </Button>
          <Button size="sm" onClick={onPublish} disabled={publishing}>
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
            Discard
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}