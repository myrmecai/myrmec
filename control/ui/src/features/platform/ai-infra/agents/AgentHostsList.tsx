// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  agentHostsApi,
  agentProfilesApi,
  projectsApi,
  type AgentHost,
  type AgentHostWithKey,
  type AgentWorker,
  type AgentWorkerStatus,
  type CreateAgentHostRequest,
  type UpdateAgentHostRequest,
  type AgentProfile,
  type Project,
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  Pencil,
  Trash2,
  Key,
  Copy,
  Check,
  Bot,
  Activity,
  AlertCircle,
  Server,
} from 'lucide-react'
import { WORKER_STATUS_STYLES } from './shared/constants'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

export function AgentHostsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editAgentHost, setEditAgentHost] = useState<AgentHost | null>(null)
  const [createdAgentHost, setCreatedAgentHost] = useState<AgentHostWithKey | null>(null)
  const [regeneratedKey, setRegeneratedKey] = useState<{ agentHostName: string; key: string } | null>(null)
  const [workersAgentHost, setWorkersAgentHost] = useState<AgentHost | null>(null)

  const { data: agentHosts, isLoading, error } = useQuery({
    queryKey: ['agent-hosts'],
    queryFn: agentHostsApi.list,
  })

  const { data: profiles } = useQuery({
    queryKey: ['agent-profiles', true],
    queryFn: () => agentProfilesApi.list(true),
  })

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.list,
  })

  const createMutation = useMutation({
    mutationFn: agentHostsApi.create,
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['agent-hosts'] })
      setCreateOpen(false)
      setCreatedAgentHost(result)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateAgentHostRequest }) =>
      agentHostsApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hosts'] })
      setEditAgentHost(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: agentHostsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-hosts'] })
    },
  })

  const regenerateKeyMutation = useMutation({
    mutationFn: agentHostsApi.regenerateKey,
    onSuccess: (result, agentHostId) => {
      const agentHost = agentHosts?.find(a => a.id === agentHostId)
      setRegeneratedKey({ agentHostName: agentHost?.name || 'Agent Host', key: result.registrationKey })
    },
  })

  const columns: ColumnDef<AgentHost>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <Bot className="h-4 w-4 text-muted-foreground" />
          <div>
            <div className="font-medium">{row.original.name}</div>
            {row.original.description && (
              <div className="text-xs text-muted-foreground truncate max-w-[200px]">
                {row.original.description}
              </div>
            )}
          </div>
        </div>
      ),
    },
    {
      accessorKey: 'profileName',
      header: 'Profile',
      cell: ({ row }) => <Badge variant="outline">{row.original.profileName || 'Unknown'}</Badge>,
    },
    {
      accessorKey: 'projectName',
      header: 'Project',
      cell: ({ row }) =>
        row.original.projectName ? (
          <span className="text-sm">{row.original.projectName}</span>
        ) : (
          <span className="text-muted-foreground text-sm">System-wide</span>
        ),
    },
    {
      id: 'instances',
      header: 'Instances',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex items-center gap-1">
          <Activity className="h-3 w-3" />
          <span className={row.original.activeInstanceCount > 0 ? 'text-green-600' : 'text-muted-foreground'}>
            {row.original.activeInstanceCount} / {row.original.maxAgents}
          </span>
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) =>
        row.original.status === 'ACTIVE' ? (
          <Badge variant="default" className="bg-green-600">Active</Badge>
        ) : (
          <Badge variant="secondary">Inactive</Badge>
        ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const agentHost = row.original
        return (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              title="View workers"
              onClick={() => setWorkersAgentHost(agentHost)}
            >
              <Server className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Regenerate key"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Regenerate Key',
                  message: 'Generate a new registration key? The old key will be invalidated.',
                  severity: 'warning',
                  type: 'warning',
                  confirmLabel: 'Regenerate',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) regenerateKeyMutation.mutate(agentHost.id)
              }}
            >
              <Key className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditAgentHost(agentHost)}
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Delete"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Agent Host',
                  message: 'Delete this agent host and all its instances? This cannot be undone.',
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(agentHost.id)
              }}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        )
      },
    },
  ], [agentHosts, regenerateKeyMutation, deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading agent hosts...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load agent hosts</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Agent Hosts</h1>
          <p className="text-muted-foreground">
            Manage deployable AI agent workers
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Agent Host
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <AgentHostForm
              profiles={profiles || []}
              projects={projects || []}
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Agent Hosts</CardTitle>
          <CardDescription>
            {agentHosts?.length || 0} agent hosts configured
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={agentHosts ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Agent Host Dialog */}
      <Dialog open={!!editAgentHost} onOpenChange={(open) => !open && setEditAgentHost(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editAgentHost && (
            <AgentHostEditForm
              agentHost={editAgentHost}
              profiles={profiles || []}
              projects={projects || []}
              onSubmit={(data) => updateMutation.mutate({ id: editAgentHost.id, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* Worker Replicas Dialog */}
      <Dialog open={!!workersAgentHost} onOpenChange={(open) => !open && setWorkersAgentHost(null)}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          {workersAgentHost && (
            <WorkersDialog agentHost={workersAgentHost} />
          )}
        </DialogContent>
      </Dialog>

      {/* Registration Key Dialog (shown after create) */}
      <Dialog open={!!createdAgentHost} onOpenChange={(open) => !open && setCreatedAgentHost(null)}>
        <DialogContent>
          <RegistrationKeyDisplay
            agentHostName={createdAgentHost?.agent.name || ''}
            registrationKey={createdAgentHost?.registrationKey || ''}
            onClose={() => setCreatedAgentHost(null)}
          />
        </DialogContent>
      </Dialog>

      {/* Regenerated Key Dialog */}
      <Dialog open={!!regeneratedKey} onOpenChange={(open) => !open && setRegeneratedKey(null)}>
        <DialogContent>
          <RegistrationKeyDisplay
            agentHostName={regeneratedKey?.agentHostName || ''}
            registrationKey={regeneratedKey?.key || ''}
            onClose={() => setRegeneratedKey(null)}
            isRegenerated
          />
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

function WorkerStatusBadge({ status }: { status: AgentWorkerStatus | null }) {
  if (!status) return <Badge variant="secondary">Unknown</Badge>
  return <Badge className={WORKER_STATUS_STYLES[status]}>{status}</Badge>
}

function formatTimestamp(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

function WorkersDialog({ agentHost }: { agentHost: AgentHost }) {
  const { data: workers, isLoading, error } = useQuery({
    queryKey: ['agent-workers', agentHost.id],
    queryFn: () => agentHostsApi.workers(agentHost.id),
    refetchInterval: 5000,
  })

  const { data: health } = useQuery({
    queryKey: ['agent-health', agentHost.id],
    queryFn: () => agentHostsApi.health(agentHost.id),
    refetchInterval: 5000,
  })

  const staleByInstance = new Map(
    (health?.instances ?? []).map((i) => [i.instanceId, i.stale]),
  )

  return (
    <>
      <DialogHeader>
        <DialogTitle>Workers — {agentHost.name}</DialogTitle>
        <DialogDescription>
          Ephemeral worker replicas and their runtime state. Refreshes every 5s.
        </DialogDescription>
      </DialogHeader>

      {health && health.totalInstances > 0 && (
        <div
          className="grid grid-cols-3 gap-2 sm:grid-cols-6"
          data-testid="agent-health-summary"
        >
          <HealthStat label="Online" value={health.onlineInstances} />
          <HealthStat label="Idle" value={health.idleInstances} />
          <HealthStat label="Busy" value={health.busyInstances} />
          <HealthStat
            label="Stale"
            value={health.staleInstances}
            tone={health.staleInstances > 0 ? 'warn' : 'default'}
          />
          <HealthStat label="Queue depth" value={health.queueDepth} />
          <HealthStat
            label="Last heartbeat"
            value={formatTimestamp(health.latestHeartbeatAt)}
          />
        </div>
      )}

      {isLoading && (
        <div className="text-muted-foreground py-6">Loading workers…</div>
      )}
      {error && (
        <div className="text-destructive py-6">Failed to load workers</div>
      )}
      {workers && (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Status</TableHead>
              <TableHead>Hostname</TableHead>
              <TableHead>Conversation</TableHead>
              <TableHead>Runtime</TableHead>
              <TableHead>Last heartbeat</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {workers.map((w: AgentWorker) => (
              <TableRow key={w.id}>
                <TableCell>
                  <div className="flex items-center gap-1.5">
                    <WorkerStatusBadge status={w.status} />
                    {staleByInstance.get(w.id) && (
                      <Badge variant="outline" className="border-amber-500 text-amber-600">
                        Stale
                      </Badge>
                    )}
                  </div>
                </TableCell>
                <TableCell className="text-sm">{w.hostname || '—'}</TableCell>
                <TableCell className="font-mono text-xs">
                  {w.conversationId ? w.conversationId.slice(0, 8) : '—'}
                </TableCell>
                <TableCell className="text-sm">{w.runtimeVersion || '—'}</TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {formatTimestamp(w.lastHeartbeatAt)}
                </TableCell>
              </TableRow>
            ))}
            {workers.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="text-center text-muted-foreground">
                  No worker replicas have registered for this host yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      )}
    </>
  )
}

function HealthStat({
  label,
  value,
  tone = 'default',
}: {
  label: string
  value: string | number
  tone?: 'default' | 'warn'
}) {
  return (
    <div className="rounded-md border bg-card px-3 py-2">
      <div className="text-muted-foreground text-xs">{label}</div>
      <div
        className={
          tone === 'warn'
            ? 'text-amber-600 text-sm font-semibold'
            : 'text-sm font-semibold'
        }
      >
        {value}
      </div>
    </div>
  )
}

interface AgentHostFormProps {
  profiles: AgentProfile[]
  projects: Project[]
  onSubmit: (data: CreateAgentHostRequest) => void
  isLoading: boolean
  error?: string
}

function AgentHostForm({ profiles, projects, onSubmit, isLoading, error }: AgentHostFormProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [profileId, setProfileId] = useState('')
  const [projectId, setProjectId] = useState<string>('')
  const [maxInstances, setMaxInstances] = useState('1')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      profileId,
      projectId: projectId || undefined,
      maxAgents: parseInt(maxInstances) || 1,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Agent Host</DialogTitle>
        <DialogDescription>
          Create a new agent worker. A registration key will be generated.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md flex items-center gap-2">
            <AlertCircle className="h-4 w-4" />
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="name">Name<RequiredMark /></Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Code Generator v2"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Agent purpose and configuration..."
            rows={3}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="profile">Profile<RequiredMark /></Label>
          <select
            id="profile"
            value={profileId}
            onChange={(e) => setProfileId(e.target.value)}
            required
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">Select a profile</option>
            {profiles.map((profile) => (
              <option key={profile.id} value={profile.id}>
                {profile.name}
              </option>
            ))}
          </select>
          <p className="text-xs text-muted-foreground">
            Profile defines agent capabilities and tools
          </p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="project">Project (optional)</Label>
          <select
            id="project"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">System-wide (no project)</option>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="maxInstances">Max Instances</Label>
          <Input
            id="maxInstances"
            type="number"
            min="1"
            value={maxInstances}
            onChange={(e) => setMaxInstances(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            Maximum concurrent instances allowed
          </p>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading || !profileId}>
          {isLoading ? 'Creating...' : 'Create Agent Host'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface AgentHostEditFormProps {
  agentHost: AgentHost
  profiles: AgentProfile[]
  projects: Project[]
  onSubmit: (data: UpdateAgentHostRequest) => void
  isLoading: boolean
  error?: string
}

function AgentHostEditForm({ agentHost, profiles, projects, onSubmit, isLoading, error }: AgentHostEditFormProps) {
  const [name, setName] = useState(agentHost.name)
  const [description, setDescription] = useState(agentHost.description || '')
  const [profileId, setProfileId] = useState(agentHost.profileId)
  const [projectId, setProjectId] = useState(agentHost.projectId || '')
  const [maxInstances, setMaxInstances] = useState(String(agentHost.maxAgents))
  const [status, setStatus] = useState(agentHost.status)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      profileId,
      projectId: projectId || undefined,
      maxAgents: parseInt(maxInstances) || 1,
      status,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Agent Host</DialogTitle>
        <DialogDescription>Update agent host configuration</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md flex items-center gap-2">
            <AlertCircle className="h-4 w-4" />
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="edit-name">Name</Label>
          <Input
            id="edit-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-description">Description</Label>
          <Textarea
            id="edit-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-profile">Profile</Label>
          <select
            id="edit-profile"
            value={profileId}
            onChange={(e) => setProfileId(e.target.value)}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          >
            {profiles.map((profile) => (
              <option key={profile.id} value={profile.id}>
                {profile.name}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-project">Project</Label>
          <select
            id="edit-project"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">System-wide (no project)</option>
            {projects.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-maxInstances">Max Instances</Label>
          <Input
            id="edit-maxInstances"
            type="number"
            min="1"
            value={maxInstances}
            onChange={(e) => setMaxInstances(e.target.value)}
          />
        </div>
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="edit-active"
            checked={status === 'ACTIVE'}
            onChange={(e) => setStatus(e.target.checked ? 'ACTIVE' : 'INACTIVE')}
            className="h-4 w-4"
          />
          <Label htmlFor="edit-active">Active</Label>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Save Changes'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface RegistrationKeyDisplayProps {
  agentHostName: string
  registrationKey: string
  onClose: () => void
  isRegenerated?: boolean
}

function RegistrationKeyDisplay({ agentHostName, registrationKey, onClose, isRegenerated }: RegistrationKeyDisplayProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(registrationKey)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>
          {isRegenerated ? 'New Registration Key' : 'Agent Host Created'}
        </DialogTitle>
        <DialogDescription>
          {isRegenerated
            ? `New registration key for "${agentHostName}". The old key is now invalid.`
            : `Agent Host "${agentHostName}" has been created successfully.`}
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="p-4 bg-amber-50 dark:bg-amber-950 border border-amber-200 dark:border-amber-800 rounded-md">
          <div className="flex items-center gap-2 text-amber-800 dark:text-amber-200 mb-2">
            <AlertCircle className="h-4 w-4" />
            <span className="font-medium">Save this key now!</span>
          </div>
          <p className="text-sm text-amber-700 dark:text-amber-300">
            This is the only time you will see this key. Store it securely.
          </p>
        </div>
        <div className="space-y-2">
          <Label>Registration Key</Label>
          <div className="flex gap-2">
            <Input
              value={registrationKey}
              readOnly
              className="font-mono text-sm"
            />
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={handleCopy}
            >
              {copied ? <Check className="h-4 w-4 text-green-600" /> : <Copy className="h-4 w-4" />}
            </Button>
          </div>
        </div>
      </div>
      <DialogFooter>
        <Button onClick={onClose}>Done</Button>
      </DialogFooter>
    </>
  )
}