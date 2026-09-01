// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  assistantsApi,
  projectsApi,
  agentProfilesApi,
  type Assistant,
  type CreateAssistantRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import { Textarea } from '@/components/ui/textarea'
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
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
import {
  Plus,
  Pencil,
  Power,
  PowerOff,
  Archive,
  ArchiveRestore,
  MessageSquare,
} from 'lucide-react'
import { dialogService } from '@/services/dialog-service'

export type AssistantsSearch = {
  projectId?: string
}

export function validateAssistantsSearch(
  search: Record<string, unknown>,
): AssistantsSearch {
  return {
    projectId: typeof search.projectId === 'string' ? search.projectId : undefined,
  }
}

type AssistantStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED' | 'ARCHIVED'

function statusOf(a: Assistant): AssistantStatus {
  if (a.archivedAt) return 'ARCHIVED'
  if (a.disabled) return 'DISABLED'
  if (a.currentVersionId) return 'PUBLISHED'
  return 'DRAFT'
}

function StatusBadge({ status }: { status: AssistantStatus }) {
  const variant =
    status === 'PUBLISHED'
      ? 'default'
      : status === 'DRAFT'
        ? 'secondary'
        : 'outline'
  return <Badge variant={variant}>{status}</Badge>
}

export function AssistantsList() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const search = useSearch({ strict: false })
  const [createOpen, setCreateOpen] = useState(false)

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => projectsApi.list(),
  })

  // useSearch({ strict: false }) is untyped; coerce to the validated shape.
  const searchProjectId =
    typeof search.projectId === 'string' ? search.projectId : undefined

  // Default the selected project to the first accessible one once loaded.
  const projectId =
    searchProjectId ?? (projects && projects.length > 0 ? projects[0].id : undefined)

  const setProjectId = (id: string) => {
    navigate({ to: '/assistants', search: { projectId: id } })
  }

  const {
    data: assistants,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['assistants', projectId],
    queryFn: () => assistantsApi.list(projectId!),
    enabled: !!projectId,
  })

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['assistants', projectId] })

  const createMutation = useMutation({
    mutationFn: (data: CreateAssistantRequest) => assistantsApi.create(data),
    onSuccess: (created) => {
      invalidate()
      setCreateOpen(false)
      navigate({
        to: '/assistants/$assistantId/edit',
        params: { assistantId: created.id },
      })
    },
  })

  const disableMutation = useMutation({
    mutationFn: ({ id, disabled }: { id: string; disabled: boolean }) =>
      assistantsApi.setDisabled(id, disabled),
    onSuccess: invalidate,
  })

  const archiveMutation = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      archived ? assistantsApi.archive(id) : assistantsApi.unarchive(id),
    onSuccess: invalidate,
  })

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Conversation Management</h1>
          <p className="text-muted-foreground">
            Author and version the assistants users converse with
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button disabled={!projectId}>
              <Plus className="h-4 w-4 mr-2" />
              New Assistant
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-lg">
            {projectId && (
              <CreateAssistantForm
                projectId={projectId}
                onSubmit={(data) => createMutation.mutate(data)}
                isLoading={createMutation.isPending}
                error={createMutation.error?.message}
              />
            )}
          </DialogContent>
        </Dialog>
      </div>

      <div className="mb-6 flex items-center gap-3">
        <Label htmlFor="project-select" className="text-sm">
          Project
        </Label>
        <Select value={projectId} onValueChange={setProjectId}>
          <SelectTrigger id="project-select" className="w-[280px]">
            <SelectValue placeholder="Select a project" />
          </SelectTrigger>
          <SelectContent>
            {projects?.map((p) => (
              <SelectItem key={p.id} value={p.id}>
                {p.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Assistants</CardTitle>
          <CardDescription>
            {assistants?.length ?? 0} assistant(s) in this project
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!projectId ? (
            <div className="text-muted-foreground py-8 text-center">
              Select a project to view its assistants.
            </div>
          ) : isLoading ? (
            <div className="text-muted-foreground py-8 text-center">
              Loading assistants…
            </div>
          ) : error ? (
            <div className="text-destructive py-8 text-center">
              Failed to load assistants.
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Updated</TableHead>
                  <TableHead className="w-[160px]">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {assistants?.map((a) => {
                  const status = statusOf(a)
                  const isArchived = status === 'ARCHIVED'
                  return (
                    <TableRow key={a.id}>
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2">
                          <MessageSquare className="h-4 w-4 text-muted-foreground" />
                          <div>
                            <div>{a.name}</div>
                            {a.description && (
                              <div className="text-xs text-muted-foreground truncate max-w-[280px]">
                                {a.description}
                              </div>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={status} />
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {a.updatedAt
                          ? new Date(a.updatedAt).toLocaleDateString()
                          : new Date(a.createdAt).toLocaleDateString()}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() =>
                              navigate({
                                to: '/assistants/$assistantId/edit',
                                params: { assistantId: a.id },
                              })
                            }
                            title="Edit"
                          >
                            <Pencil className="h-4 w-4" />
                          </Button>
                          {!isArchived &&
                            (a.disabled ? (
                              <Button
                                variant="ghost"
                                size="icon"
                                onClick={() =>
                                  disableMutation.mutate({
                                    id: a.id,
                                    disabled: false,
                                  })
                                }
                                title="Enable"
                              >
                                <Power className="h-4 w-4" />
                              </Button>
                            ) : (
                              <Button
                                variant="ghost"
                                size="icon"
                                onClick={() =>
                                  disableMutation.mutate({
                                    id: a.id,
                                    disabled: true,
                                  })
                                }
                                title="Disable"
                              >
                                <PowerOff className="h-4 w-4" />
                              </Button>
                            ))}
                          {isArchived ? (
                            <Button
                              variant="ghost"
                              size="icon"
                              onClick={() =>
                                archiveMutation.mutate({
                                  id: a.id,
                                  archived: false,
                                })
                              }
                              title="Restore"
                            >
                              <ArchiveRestore className="h-4 w-4" />
                            </Button>
                          ) : (
                            <Button
                              variant="ghost"
                              size="icon"
                              onClick={async () => {
                                const confirmed = await dialogService.showConfirmDialog({
                                  title: 'Archive Assistant',
                                  message: `Archive assistant "${a.name}"? It can be restored later.`,
                                  severity: 'warning',
                                  type: 'warning',
                                  confirmLabel: 'Archive',
                                  cancelLabel: 'Cancel',
                                })
                                if (confirmed) archiveMutation.mutate({ id: a.id, archived: true })
                              }}
                              title="Archive"
                            >
                              <Archive className="h-4 w-4" />
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
                {assistants?.length === 0 && (
                  <TableRow>
                    <TableCell
                      colSpan={4}
                      className="text-center text-muted-foreground"
                    >
                      No assistants yet. Create your first assistant to start a
                      conversational service.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function CreateAssistantForm({
  projectId,
  onSubmit,
  isLoading,
  error,
}: {
  projectId: string
  onSubmit: (data: CreateAssistantRequest) => void
  isLoading: boolean
  error?: string
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [agentProfileId, setAgentProfileId] = useState('')

  const { data: profiles } = useQuery({
    queryKey: ['agent-profiles', 'active'],
    queryFn: () => agentProfilesApi.list(true),
  })

  const canSubmit = name.trim().length > 0 && agentProfileId.length > 0

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        if (!canSubmit) return
        onSubmit({
          projectId,
          name: name.trim(),
          description: description.trim() || undefined,
          agentProfileId,
        })
      }}
    >
      <DialogHeader>
        <DialogTitle>New Assistant</DialogTitle>
        <DialogDescription>
          Pick the brain (agent profile) for this assistant. You can refine its
          behaviour in the editor before publishing.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="name">Name<RequiredMark /></Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Support Assistant"
            maxLength={120}
            autoFocus
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What does this assistant help with?"
            maxLength={4000}
            rows={3}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="profile">Agent Profile (brain)<RequiredMark /></Label>
          <Select value={agentProfileId} onValueChange={setAgentProfileId}>
            <SelectTrigger id="profile">
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
        {error && <p className="text-sm text-destructive">{error}</p>}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={!canSubmit || isLoading}>
          {isLoading ? 'Creating…' : 'Create'}
        </Button>
      </DialogFooter>
    </form>
  )
}