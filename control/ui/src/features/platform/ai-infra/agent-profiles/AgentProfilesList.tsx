// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import React, { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  agentProfilesApi,
  modelsApi,
  toolsApi,
  type AgentProfile,
  type CreateAgentProfileRequest,
  type UpdateAgentProfileRequest,
  type Model,
  type Tool,
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
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
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
  Pencil,
  Trash2,
  CheckCircle,
  XCircle,
  Power,
  PowerOff,
  Cpu,
  Wrench,
} from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

export function AgentProfilesList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editProfile, setEditProfile] = useState<AgentProfile | null>(null)

  const {
    data: profiles,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['agent-profiles'],
    queryFn: () => agentProfilesApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: agentProfilesApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateAgentProfileRequest }) =>
      agentProfilesApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
      setEditProfile(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: agentProfilesApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
    },
  })

  const activateMutation = useMutation({
    mutationFn: agentProfilesApi.activate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: agentProfilesApi.deactivate,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
    },
  })

  const columns: ColumnDef<AgentProfile>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <div>
          <div className="font-medium">
            {row.original.name}
            {/* §16.1 version banner — the published version is what runs */}
            {row.original.publishedVersionNumber != null ? (
              <Badge variant="outline" className="ml-2 text-xs font-mono">
                v{row.original.publishedVersionNumber}
              </Badge>
            ) : (
              <Badge variant="destructive" className="ml-2 text-xs">
                unpublished
              </Badge>
            )}
          </div>
          {row.original.description && (
            <div className="text-xs text-muted-foreground truncate max-w-[200px]">
              {row.original.description}
            </div>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'defaultModel',
      header: 'Model',
      cell: ({ row }) =>
        row.original.defaultModel ? (
          <Badge variant="secondary" className="text-xs font-mono">
            {row.original.defaultModel}
          </Badge>
        ) : (
          <span className="text-sm">-</span>
        ),
    },
    {
      id: 'capabilities',
      header: 'Capabilities',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-1 max-w-[200px]">
          {row.original.capabilities?.slice(0, 3).map((cap) => (
            <Badge key={cap} variant="secondary" className="text-xs">
              <Cpu className="h-3 w-3 mr-1" />
              {cap}
            </Badge>
          ))}
          {(row.original.capabilities?.length || 0) > 3 && (
            <Badge variant="outline" className="text-xs">
              +{row.original.capabilities!.length - 3}
            </Badge>
          )}
          {!row.original.capabilities?.length && (
            <span className="text-muted-foreground text-sm">-</span>
          )}
        </div>
      ),
    },
    {
      id: 'tools',
      header: 'Tools',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex flex-wrap gap-1 max-w-[200px]">
          {row.original.toolCodes?.slice(0, 3).map((tool) => (
            <Badge key={tool} variant="outline" className="text-xs">
              <Wrench className="h-3 w-3 mr-1" />
              {tool}
            </Badge>
          ))}
          {(row.original.toolCodes?.length || 0) > 3 && (
            <Badge variant="outline" className="text-xs">
              +{row.original.toolCodes!.length - 3}
            </Badge>
          )}
          {!row.original.toolCodes?.length && (
            <span className="text-muted-foreground text-sm">-</span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) => <StatusBadge status={row.original.status} />,
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const profile = row.original
        return (
          <div className="flex items-center gap-1">
            {profile.status === 'ACTIVE' ? (
              <Button
                variant="ghost"
                size="icon"
                onClick={() => deactivateMutation.mutate(profile.id)}
                title="Deactivate"
              >
                <PowerOff className="h-4 w-4" />
              </Button>
            ) : (
              <Button
                variant="ghost"
                size="icon"
                onClick={() => activateMutation.mutate(profile.id)}
                title="Activate"
              >
                <Power className="h-4 w-4" />
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditProfile(profile)}
              title="Edit"
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Agent Profile',
                  message: 'Are you sure you want to delete this profile? This cannot be undone.',
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(profile.id)
              }}
              title="Delete"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        )
      },
    },
  ], [activateMutation, deactivateMutation, deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading agent profiles...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load agent profiles</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Agent Profiles</h1>
          <p className="text-muted-foreground">
            Define agent capabilities and supported tools
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Profile
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <ProfileForm
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Profiles</CardTitle>
          <CardDescription>
            {profiles?.length || 0} agent profiles configured
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={profiles ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Profile Dialog */}
      <Dialog
        open={!!editProfile}
        onOpenChange={(open) => !open && setEditProfile(null)}
      >
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editProfile && (
            <ProfileForm
              profile={editProfile}
              onSubmit={(data) =>
                updateMutation.mutate({ id: editProfile.id, data })
              }
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

function StatusBadge({ status }: { status: 'ACTIVE' | 'INACTIVE' }) {
  if (status === 'ACTIVE') {
    return (
      <span className="inline-flex items-center gap-1 text-green-600 text-sm">
        <CheckCircle className="h-4 w-4" />
        Active
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1 text-muted-foreground text-sm">
      <XCircle className="h-4 w-4" />
      Inactive
    </span>
  )
}

interface ProfileFormProps {
  profile?: AgentProfile
  onSubmit: (data: CreateAgentProfileRequest | UpdateAgentProfileRequest) => void
  isLoading: boolean
  error?: string
}

function ProfileForm({ profile, onSubmit, isLoading, error }: ProfileFormProps) {
  const [capabilities, setCapabilities] = useState<string>(
    profile?.capabilities?.join('\n') || ''
  )
  const [toolCodes, setToolCodes] = useState<string[]>(
    profile?.toolCodes ?? []
  )
  const [systemPrompt, setSystemPrompt] = useState<string>(
    profile?.systemPrompt || ''
  )
  const [defaultModel, setDefaultModel] = useState<string>(
    profile?.defaultModel || ''
  )

  const { data: models, isLoading: modelsLoading } = useQuery({
    queryKey: ['models'],
    queryFn: () => modelsApi.list(),
  })

  const { data: tools } = useQuery({
    queryKey: ['tools'],
    queryFn: () => toolsApi.list(),
  })

  const toolOptions: MultiSelectOption[] = React.useMemo(
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

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)

    const data: CreateAgentProfileRequest = {
      name: formData.get('name') as string,
      description: (formData.get('description') as string) || undefined,
      capabilities: capabilities
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean),
      toolCodes,
      systemPrompt: systemPrompt.trim() || undefined,
      defaultModel: defaultModel || undefined,
    }

    onSubmit(data)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>
          {profile ? 'Edit Agent Profile' : 'Create Agent Profile'}
        </DialogTitle>
        <DialogDescription>
          {profile
            ? 'Update the agent profile configuration'
            : 'Define a new agent profile with capabilities and tools'}
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 py-4">
        <div className="grid gap-2">
          <Label htmlFor="name">Name<RequiredMark /></Label>
          <Input
            id="name"
            name="name"
            defaultValue={profile?.name}
            placeholder="Python ML Agent"
            required
          />
        </div>

        <div className="grid gap-2">
          <Label htmlFor="description">Description</Label>
          <Textarea
            id="description"
            name="description"
            defaultValue={profile?.description || ''}
            placeholder="Agent profile for machine learning tasks with Python"
            rows={2}
          />
        </div>

        <div className="grid gap-2">
          <Label htmlFor="defaultModel">
            Default Model
            <span className="text-muted-foreground text-xs ml-2">
              (LLM for task execution)
            </span>
          </Label>
          <Select value={defaultModel} onValueChange={setDefaultModel}>
            <SelectTrigger>
              <SelectValue placeholder={modelsLoading ? "Loading models..." : "Select a model"} />
            </SelectTrigger>
            <SelectContent>
              {models?.filter((m: Model) => m.status === 'ACTIVE').map((model: Model) => (
                <SelectItem key={model.code} value={model.code}>
                  {model.name} ({model.code})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            The LLM model that agents with this profile will use for task execution.
          </p>
        </div>

        <div className="grid gap-2">
          <Label htmlFor="systemPrompt">
            System Prompt
            <span className="text-muted-foreground text-xs ml-2">
              (expertise/personality)
            </span>
          </Label>
          <Textarea
            id="systemPrompt"
            value={systemPrompt}
            onChange={(e) => setSystemPrompt(e.target.value)}
            placeholder="You are an expert Python developer with deep knowledge of machine learning frameworks. You write clean, well-documented code following PEP 8 conventions."
            rows={4}
          />
          <p className="text-xs text-muted-foreground">
            Defines the agent's expertise and personality. This prompt is sent to the LLM for all tasks assigned to agents with this profile.
          </p>
        </div>

        <div className="grid gap-2">
          <Label htmlFor="capabilities">
            Capabilities
            <span className="text-muted-foreground text-xs ml-2">
              (one per line)
            </span>
          </Label>
          <Textarea
            id="capabilities"
            value={capabilities}
            onChange={(e) => setCapabilities(e.target.value)}
            placeholder="python:3.11&#10;cuda:12.4&#10;docker"
            rows={4}
            className="font-mono text-sm"
          />
          <p className="text-xs text-muted-foreground">
            Software, libraries, or hardware capabilities.
            Examples: python:3.11, cuda:12.4, docker, gpu:nvidia
          </p>
        </div>

        <div className="grid gap-2">
          <Label htmlFor="toolCodes">
            Tools
            <span className="text-muted-foreground text-xs ml-2">
              (from the tools registry)
            </span>
          </Label>
          <MultiSelect
            options={toolOptions}
            selected={toolCodes}
            onChange={setToolCodes}
            placeholder="Select tools..."
            searchPlaceholder="Search tools by name or code..."
          />
          <p className="text-xs text-muted-foreground">
            Tools available to agents with this profile. Only active tools are shown.
          </p>
        </div>
      </div>

      {error && (
        <div className="text-destructive text-sm mb-4">{error}</div>
      )}

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : profile ? 'Update Profile' : 'Create Profile'}
        </Button>
      </DialogFooter>
    </form>
  )
}