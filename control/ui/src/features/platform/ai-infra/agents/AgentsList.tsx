// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  agentsApi,
  agentProfilesApi,
  projectsApi,
  type Agent,
  type AgentWithKey,
  type AgentWorker,
  type AgentWorkerStatus,
  type CreateAgentRequest,
  type UpdateAgentRequest,
  type AgentProfile,
  type Project,
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
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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

export function AgentsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editAgent, setEditAgent] = useState<Agent | null>(null)
  const [createdAgent, setCreatedAgent] = useState<AgentWithKey | null>(null)
  const [regeneratedKey, setRegeneratedKey] = useState<{ agentName: string; key: string } | null>(null)
  const [workersAgent, setWorkersAgent] = useState<Agent | null>(null)

  const { data: agents, isLoading, error } = useQuery({
    queryKey: ['agents'],
    queryFn: agentsApi.list,
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
    mutationFn: agentsApi.create,
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['agents'] })
      setCreateOpen(false)
      setCreatedAgent(result)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateAgentRequest }) =>
      agentsApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] })
      setEditAgent(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: agentsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] })
    },
  })

  const regenerateKeyMutation = useMutation({
    mutationFn: agentsApi.regenerateKey,
    onSuccess: (result, agentId) => {
      const agent = agents?.find(a => a.id === agentId)
      setRegeneratedKey({ agentName: agent?.name || 'Agent', key: result.registrationKey })
    },
  })

  const columns: ColumnDef<Agent>[] = useMemo(() => [
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
        const agent = row.original
        return (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              title="View workers"
              onClick={() => setWorkersAgent(agent)}
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
                if (confirmed) regenerateKeyMutation.mutate(agent.id)
              }}
            >
              <Key className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditAgent(agent)}
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Agent',
                  message: 'Delete this agent and all its instances? This cannot be undone.',
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(agent.id)
              }}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        )
      },
    },
  ], [agents, regenerateKeyMutation, deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading agents...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load agents</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Agents</h1>
          <p className="text-muted-foreground">
            Manage deployable AI agent workers
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Agent
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <AgentForm
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
          <CardTitle>All Agents</CardTitle>
          <CardDescription>
            {agents?.length || 0} agents configured
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={agents ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Agent Dialog */}
      <Dialog open={!!editAgent} onOpenChange={(open) => !open && setEditAgent(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editAgent && (
            <AgentEditForm
              agent={editAgent}
              profiles={profiles || []}
              projects={projects || []}
              onSubmit={(data) => updateMutation.mutate({ id: editAgent.id, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* Worker Replicas Dialog */}
      <Dialog open={!!workersAgent} onOpenChange={(open) => !open && setWorkersAgent(null)}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          {workersAgent && (
            <WorkersDialog agent={workersAgent} />
          )}
        </DialogContent>
      </Dialog>

      {/* Registration Key Dialog (shown after create) */}
      <Dialog open={!!createdAgent} onOpenChange={(open) => !open && setCreatedAgent(null)}>
        <DialogContent>
          <RegistrationKeyDisplay
            agentName={createdAgent?.agent.name || ''}
            registrationKey={createdAgent?.registrationKey || ''}
            onClose={() => setCreatedAgent(null)}
          />
        </DialogContent>
      </Dialog>

      {/* Regenerated Key Dialog */}
      <Dialog open={!!regeneratedKey} onOpenChange={(open) => !open && setRegeneratedKey(null)}>
        <DialogContent>
          <RegistrationKeyDisplay
            agentName={regeneratedKey?.agentName || ''}
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

function WorkersDialog({ agent }: { agent: Agent }) {
  const { data: workers, isLoading, error } = useQuery({
    queryKey: ['agent-workers', agent.id],
    queryFn: () => agentsApi.workers(agent.id),
    refetchInterval: 5000,
  })

  const { data: health } = useQuery({
    queryKey: ['agent-health', agent.id],
    queryFn: () => agentsApi.health(agent.id),
    refetchInterval: 5000,
  })

  const staleByInstance = new Map(
    (health?.instances ?? []).map((i) => [i.instanceId, i.stale]),
  )

  return (
    <>
      <DialogHeader>
        <DialogTitle>Workers — {agent.name}</DialogTitle>
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

interface AgentFormProps {
  profiles: AgentProfile[]
  projects: Project[]
  onSubmit: (data: CreateAgentRequest) => void
  isLoading: boolean
  error?: string
}

function AgentForm({ profiles, projects, onSubmit, isLoading, error }: AgentFormProps) {
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
        <DialogTitle>New Agent</DialogTitle>
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
          <Label htmlFor="name">Name</Label>
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
          <Label htmlFor="profile">Profile</Label>
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
          {isLoading ? 'Creating...' : 'Create Agent'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface AgentEditFormProps {
  agent: Agent
  profiles: AgentProfile[]
  projects: Project[]
  onSubmit: (data: UpdateAgentRequest) => void
  isLoading: boolean
  error?: string
}

function AgentEditForm({ agent, profiles, projects, onSubmit, isLoading, error }: AgentEditFormProps) {
  const [name, setName] = useState(agent.name)
  const [description, setDescription] = useState(agent.description || '')
  const [profileId, setProfileId] = useState(agent.profileId)
  const [projectId, setProjectId] = useState(agent.projectId || '')
  const [maxInstances, setMaxInstances] = useState(String(agent.maxAgents))
  const [status, setStatus] = useState(agent.status)

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
        <DialogTitle>Edit Agent</DialogTitle>
        <DialogDescription>Update agent configuration</DialogDescription>
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
  agentName: string
  registrationKey: string
  onClose: () => void
  isRegenerated?: boolean
}

function RegistrationKeyDisplay({ agentName, registrationKey, onClose, isRegenerated }: RegistrationKeyDisplayProps) {
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
          {isRegenerated ? 'New Registration Key' : 'Agent Created'}
        </DialogTitle>
        <DialogDescription>
          {isRegenerated
            ? `New registration key for "${agentName}". The old key is now invalid.`
            : `Agent "${agentName}" has been created successfully.`}
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