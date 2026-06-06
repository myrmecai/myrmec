import { createFileRoute } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  agentsApi,
  agentProfilesApi,
  projectsApi,
  type Agent,
  type AgentWithKey,
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
} from 'lucide-react'

export const Route = createFileRoute('/_authenticated/agents')({
  component: AgentsPage,
})

function AgentsPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editAgent, setEditAgent] = useState<Agent | null>(null)
  const [createdAgent, setCreatedAgent] = useState<AgentWithKey | null>(null)
  const [regeneratedKey, setRegeneratedKey] = useState<{ agentName: string; key: string } | null>(null)

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
    <div className="p-8">
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
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Profile</TableHead>
                <TableHead>Project</TableHead>
                <TableHead>Instances</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-[150px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {agents?.map((agent) => (
                <TableRow key={agent.id}>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Bot className="h-4 w-4 text-muted-foreground" />
                      <div>
                        <div className="font-medium">{agent.name}</div>
                        {agent.description && (
                          <div className="text-xs text-muted-foreground truncate max-w-[200px]">
                            {agent.description}
                          </div>
                        )}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">{agent.profileName || 'Unknown'}</Badge>
                  </TableCell>
                  <TableCell>
                    {agent.projectName ? (
                      <span className="text-sm">{agent.projectName}</span>
                    ) : (
                      <span className="text-muted-foreground text-sm">System-wide</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Activity className="h-3 w-3" />
                      <span className={agent.activeInstanceCount > 0 ? 'text-green-600' : 'text-muted-foreground'}>
                        {agent.activeInstanceCount} / {agent.maxInstances}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell>
                    {agent.status === 'ACTIVE' ? (
                      <Badge variant="default" className="bg-green-600">Active</Badge>
                    ) : (
                      <Badge variant="secondary">Inactive</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Regenerate key"
                        onClick={() => {
                          if (confirm('Generate a new registration key? The old key will be invalidated.')) {
                            regenerateKeyMutation.mutate(agent.id)
                          }
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
                        onClick={() => {
                          if (confirm('Delete this agent and all its instances?')) {
                            deleteMutation.mutate(agent.id)
                          }
                        }}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {agents?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground">
                    No agents configured yet. Create your first agent to get started.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
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
      maxInstances: parseInt(maxInstances) || 1,
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
  const [maxInstances, setMaxInstances] = useState(String(agent.maxInstances))
  const [status, setStatus] = useState(agent.status)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      profileId,
      projectId: projectId || undefined,
      maxInstances: parseInt(maxInstances) || 1,
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
