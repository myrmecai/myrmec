// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  agentProfilesApi,
  type AgentProfile,
  type CreateAgentProfileRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Cpu, Wrench, Plus, Trash2, Power, PowerOff, MoreVertical, Eye } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'
import { ContentAreaLayout } from '@/components/content-area-layout'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'
import { RequiredMark } from '@/components/ui/required-marks'
import { StatusBadge } from './shared/StatusBadge'

export function AgentProfilesList() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  const {
    data: profiles,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['agent-profiles'],
    queryFn: () => agentProfilesApi.list(),
  })

  // Identity-only create (design 3.3): the engine opens a v1 DRAFT when the
  // request carries no behaviour content; the draft is completed on the
  // detail page, mirroring the instruction-asset creation flow.
  const createMutation = useMutation({
    mutationFn: (data: CreateAgentProfileRequest) => agentProfilesApi.create(data),
    onSuccess: (newProfile) => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
      setCreateOpen(false)
      setToastMessage('Agent profile created - complete the draft and publish.')
      setTimeout(() => setToastMessage(null), 5000)
      navigate({
        to: '/platform/ai-infra/agent-profiles/$id',
        params: { id: newProfile.id },
      })
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

  const deleteMutation = useMutation({
    mutationFn: agentProfilesApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-profiles'] })
    },
  })

  const columns: ColumnDef<AgentProfile>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <div className="font-medium">
          <Link
            to="/platform/ai-infra/agent-profiles/$id"
            params={{ id: row.original.id }}
            className="hover:underline"
          >
            {row.original.name}
          </Link>
          {/* Version banner - the published version is what runs */}
          {row.original.publishedVersionNumber != null ? (
            <Badge variant="outline" className="ml-2 text-xs font-mono">
              v{row.original.publishedVersionNumber}
            </Badge>
          ) : (
            <Badge variant="destructive" className="ml-2 text-xs">
              unpublished
            </Badge>
          )}
          {/* Draft banner - an open draft awaits Publish/Discard */}
          {row.original.draftVersionId && (
            <Badge
              className="ml-2 text-xs font-mono bg-amber-100 text-amber-900 hover:bg-amber-100"
              title={`Draft v${row.original.draftVersionNumber} is open - publish to make it live, or discard it`}
            >
              draft v{row.original.draftVersionNumber}
            </Badge>
          )}
          {row.original.description && (
            <span className="text-xs text-muted-foreground block max-w-[220px] truncate">
              {row.original.description}
            </span>
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
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0" aria-label="More actions">
                <MoreVertical className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem asChild>
                <Link to="/platform/ai-infra/agent-profiles/$id" params={{ id: profile.id }}>
                  <Eye className="h-4 w-4 mr-2" /> Open Detail
                </Link>
              </DropdownMenuItem>
              {profile.status === 'ACTIVE' ? (
                <DropdownMenuItem onClick={() => deactivateMutation.mutate(profile.id)}>
                  <PowerOff className="h-4 w-4 mr-2" /> Deactivate
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem onClick={() => activateMutation.mutate(profile.id)}>
                  <Power className="h-4 w-4 mr-2" /> Activate
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
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
                  if (confirmed) deleteMutation.mutate(profile.id)
                }}
              >
                <Trash2 className="h-4 w-4 mr-2" /> Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
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
      <div className="space-y-6">
        {toastMessage && (
          <div
            className="fixed bottom-4 right-4 z-50 rounded-lg border bg-background p-4 shadow-lg"
            role="status"
            aria-live="polite"
          >
            <p className="text-sm">{toastMessage}</p>
          </div>
        )}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">Agent Profiles</h1>
            <p className="text-muted-foreground">
              Define agent capabilities and supported tools
            </p>
          </div>
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4 mr-2" />
            New Profile
          </Button>
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

        <CreateAgentProfileDialog
          open={createOpen}
          onOpenChange={(nextOpen) => {
            setCreateOpen(nextOpen)
            if (nextOpen) createMutation.reset()
          }}
          onCreate={(data) => createMutation.mutate(data)}
          isPending={createMutation.isPending}
          error={createMutation.error?.message}
        />
      </div>
    </ContentAreaLayout>
  )
}

/** Identity-only create dialog - behaviour is configured on the detail draft. */
function CreateAgentProfileDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
  error,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateAgentProfileRequest) => void
  isPending: boolean
  error?: string
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    // Identity-only payload: with no behaviour content the engine opens a
    // v1 draft instead of publishing (design 3.3).
    const data: CreateAgentProfileRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
    }
    onCreate(data)
    setName('')
    setDescription('')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Agent Profile</DialogTitle>
          <DialogDescription>
            Define the identity first. Complete the behaviour draft and publish on the detail page.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="profile-name">
              Name<RequiredMark />
            </Label>
            <Input
              id="profile-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Python ML Agent"
              required
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="profile-description">Description</Label>
            <Textarea
              id="profile-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Agent profile for machine learning tasks with Python"
              rows={2}
            />
          </div>
          {error && <div className="text-destructive text-sm">{error}</div>}
          <DialogFooter>
            <Button type="submit" disabled={isPending || name.trim() === ''}>
              {isPending ? 'Creating...' : 'Create Profile'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}