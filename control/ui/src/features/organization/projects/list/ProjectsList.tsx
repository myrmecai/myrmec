// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import { projectsApi, projectSecretsApi, globalSecretsApi, groupsApi, type Project, type CreateProjectRequest, type UpdateProjectRequest, type Secret, type CredentialType, type Group } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
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
  DialogTrigger,
} from '@/components/ui/dialog'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Pencil, Trash2, FolderOpen, FolderX, GitBranch, Building2, MoreVertical } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

const GIT_COMPATIBLE_TYPES: ReadonlySet<CredentialType> = new Set<CredentialType>([
  'BEARER_TOKEN',
  'USERNAME_PASSWORD',
  'SSL_PRIVATE_KEY',
])
const isGitCompatible = (t: CredentialType) => GIT_COMPATIBLE_TYPES.has(t)
const NO_CRED_VALUE = '__none__'

function WorkspaceCredentialSelect({
  projectId,
  value,
  onChange,
  idPrefix,
}: {
  projectId?: string
  value: string
  onChange: (next: string) => void
  idPrefix: string
}) {
  const { data: projectSecrets } = useQuery({
    queryKey: ['project-secrets', projectId],
    queryFn: () => projectSecretsApi.list(projectId as string),
    enabled: !!projectId,
  })
  const { data: globalSecrets } = useQuery({
    queryKey: ['global-secrets'],
    queryFn: async () => {
      try {
        return await globalSecretsApi.list()
      } catch {
        return [] as Secret[]
      }
    },
  })

  const gitSecrets: Secret[] = [
    ...(projectSecrets || []),
    ...(globalSecrets || []),
  ].filter((s) => isGitCompatible(s.type))
  const projectScoped = gitSecrets.filter((s) => s.scope === 'PROJECT')
  const globalScoped = gitSecrets.filter((s) => s.scope === 'GLOBAL')

  return (
    <div className="space-y-2">
      <Label htmlFor={`${idPrefix}-cred`} className="text-sm">Credential (optional)</Label>
      <Select
        value={value || NO_CRED_VALUE}
        onValueChange={(v) => onChange(v === NO_CRED_VALUE ? '' : v)}
      >
        <SelectTrigger id={`${idPrefix}-cred`}>
          <SelectValue placeholder="No credential (public repo)" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={NO_CRED_VALUE}>No credential (public repo)</SelectItem>
          {projectScoped.length > 0 && (
            <SelectGroup>
              <SelectLabel>Project secrets</SelectLabel>
              {projectScoped.map((s) => (
                <SelectItem key={s.id} value={s.id}>
                  {s.name} <span className="text-muted-foreground text-xs">({s.type})</span>
                </SelectItem>
              ))}
            </SelectGroup>
          )}
          {globalScoped.length > 0 && (
            <SelectGroup>
              <SelectLabel>Global secrets</SelectLabel>
              {globalScoped.map((s) => (
                <SelectItem key={s.id} value={s.id}>
                  {s.name} <span className="text-muted-foreground text-xs">({s.type})</span>
                </SelectItem>
              ))}
            </SelectGroup>
          )}
          {gitSecrets.length === 0 && (
            <div className="px-2 py-1.5 text-xs text-muted-foreground">
              No git-compatible secrets available
            </div>
          )}
        </SelectContent>
      </Select>
      <p className="text-xs text-muted-foreground">
        Used to authenticate when cloning the workspace repo. Only BEARER_TOKEN, USERNAME_PASSWORD, and SSL_PRIVATE_KEY secrets are shown.
      </p>
    </div>
  )
}

export function ProjectsList() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { isPlatformAdmin, isOrgAdmin, hasProjectRole } = useAuth()
  const [createOpen, setCreateOpen] = useState(false)
  const [editProject, setEditProject] = useState<Project | null>(null)
  const [moveProject, setMoveProject] = useState<Project | null>(null)

  const { data: projects, isLoading, error } = useQuery({
    queryKey: ['projects'],
    queryFn: projectsApi.list,
  })

  const { data: groups } = useQuery({
    queryKey: ['groups'],
    queryFn: groupsApi.list,
  })

  const groupById = new Map<string, Group>((groups ?? []).map((g) => [g.id, g]))

  const createMutation = useMutation({
    mutationFn: projectsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateProjectRequest }) =>
      projectsApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setEditProject(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: projectsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
    },
  })

  const moveMutation = useMutation({
    mutationFn: ({ id, groupId }: { id: string; groupId: string }) =>
      projectsApi.moveToGroup(id, groupId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setMoveProject(null)
    },
  })

  const columns: ColumnDef<Project>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <span className="font-medium">
          <Link to="/projects/$projectId" params={{ projectId: row.original.id }} className="hover:underline">
            {row.original.name}
          </Link>
        </span>
      ),
    },
    {
      accessorKey: 'groupId',
      header: 'Group',
      enableSorting: false,
      cell: ({ row }) => (
        <span className="inline-flex items-center gap-1 text-sm text-muted-foreground">
          <Building2 className="h-3.5 w-3.5" />
          {groupById.get(row.original.groupId)?.name ?? '—'}
        </span>
      ),
    },
    {
      accessorKey: 'description',
      header: 'Description',
      cell: ({ row }) => (
        <span className="max-w-[300px] truncate block">
          {row.original.description || <span className="text-muted-foreground">No description</span>}
        </span>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) =>
        row.original.status === 'ACTIVE' ? (
          <span className="inline-flex items-center gap-1 text-green-600">
            <FolderOpen className="h-4 w-4" />
            Active
          </span>
        ) : (
          <span className="inline-flex items-center gap-1 text-muted-foreground">
            <FolderX className="h-4 w-4" />
            Inactive
          </span>
        ),
    },
    {
      accessorKey: 'createdAt',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Created" />,
      cell: ({ row }) => new Date(row.original.createdAt).toLocaleDateString(),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const project = row.original
        const canTransfer =
          isPlatformAdmin ||
          isOrgAdmin ||
          hasProjectRole(project.id, 'PROJECT_OWNER')
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="sm" variant="ghost" className="h-8 w-8 p-0">
                <span className="sr-only">More actions</span>
                <MoreVertical className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem asChild>
                <Link to="/projects/$projectId" params={{ projectId: project.id }}>
                  View
                </Link>
              </DropdownMenuItem>
              <DropdownMenuItem asChild>
                <Link
                  to="/projects/$projectId/chat"
                  params={{ projectId: project.id }}
                  data-testid={`project-chat-${project.id}`}
                >
                  Chat
                </Link>
              </DropdownMenuItem>
              {canTransfer && (
                <DropdownMenuItem onClick={() => setMoveProject(project)}>
                  Move to group
                </DropdownMenuItem>
              )}
              <DropdownMenuItem onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Project',
                  message: 'Are you sure you want to delete this project? This cannot be undone.',
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(project.id)
              }}>
                Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )
      },
    },
  ], [groupById, isPlatformAdmin, isOrgAdmin, hasProjectRole, deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading projects...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load projects</div>
      </div>
    )
  }

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Projects</h1>
          <p className="text-muted-foreground">Manage your AI workflow projects</p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Project
            </Button>
          </DialogTrigger>
          <DialogContent>
            <CreateProjectForm
              groups={groups ?? []}
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Projects</CardTitle>
          <CardDescription>{projects?.length || 0} projects created</CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={projects ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Project Dialog */}
      <Dialog open={!!editProject} onOpenChange={(open) => !open && setEditProject(null)}>
        <DialogContent>
          {editProject && (
            <EditProjectForm
              project={editProject}
              onSubmit={(data) => updateMutation.mutate({ id: editProject.id, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* Move-to-group Dialog */}
      <Dialog open={!!moveProject} onOpenChange={(open) => !open && setMoveProject(null)}>
        <DialogContent>
          {moveProject && (
            <MoveProjectGroupForm
              project={moveProject}
              groups={groups ?? []}
              onSubmit={(groupId) => moveMutation.mutate({ id: moveProject.id, groupId })}
              isLoading={moveMutation.isPending}
              error={moveMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

const SERVICE_TYPE_OPTIONS: { value: string; label: string; hint: string }[] = [
  {
    value: 'WORKFLOW',
    label: 'Workflow',
    hint: 'Deterministic multi-step automations (Executions).',
  },
  {
    value: 'CONVERSATIONAL',
    label: 'Conversational',
    hint: 'Interactive assistants and chat sessions.',
  },
]

/**
 * Multi-select of the service types a project may host (#77). At least one
 * type must stay selected — the last-checked box is disabled so it cannot be
 * cleared, mirroring the backend "must allow at least one" rule.
 */
function ServiceTypesField({
  idPrefix,
  value,
  onChange,
}: {
  idPrefix: string
  value: string[]
  onChange: (next: string[]) => void
}) {
  const toggle = (type: string, checked: boolean) => {
    if (checked) {
      onChange([...value, type].filter((v, i, a) => a.indexOf(v) === i))
    } else {
      onChange(value.filter((v) => v !== type))
    }
  }
  return (
    <div className="space-y-2">
      <Label>Allowed service types</Label>
      <div className="space-y-2">
        {SERVICE_TYPE_OPTIONS.map((opt) => {
          const checked = value.includes(opt.value)
          const isLastChecked = checked && value.length === 1
          return (
            <label
              key={opt.value}
              htmlFor={`${idPrefix}-svc-${opt.value}`}
              className="flex items-start gap-2 text-sm"
            >
              <input
                type="checkbox"
                id={`${idPrefix}-svc-${opt.value}`}
                checked={checked}
                disabled={isLastChecked}
                onChange={(e) => toggle(opt.value, e.target.checked)}
                className="h-4 w-4 mt-0.5"
              />
              <span>
                <span className="font-medium">{opt.label}</span>
                <span className="block text-xs text-muted-foreground">{opt.hint}</span>
              </span>
            </label>
          )
        })}
      </div>
      <p className="text-xs text-muted-foreground">
        Controls which kinds of services can be created in this project. At least one is required.
      </p>
    </div>
  )
}

interface CreateProjectFormProps {
  groups: Group[]
  onSubmit: (data: CreateProjectRequest) => void
  isLoading: boolean
  error?: string
}

function CreateProjectForm({ groups, onSubmit, isLoading, error }: CreateProjectFormProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const defaultGroupId =
    groups.find((g) => g.name === 'Default')?.id ?? groups[0]?.id ?? ''
  const [groupId, setGroupId] = useState(defaultGroupId)
  const [allowedServiceTypes, setAllowedServiceTypes] = useState<string[]>([
    'WORKFLOW',
    'CONVERSATIONAL',
  ])
  const [workspaceRepoUrl, setWorkspaceRepoUrl] = useState('')
  const [workspaceRepoBranch, setWorkspaceRepoBranch] = useState('main')
  const [workspaceCredentialSecretId, setWorkspaceCredentialSecretId] = useState('')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      groupId: groupId || undefined,
      allowedServiceTypes,
      workspaceRepoUrl: workspaceRepoUrl || undefined,
      workspaceRepoBranch: workspaceRepoBranch || undefined,
      workspaceCredentialSecretId: workspaceCredentialSecretId || undefined,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Project</DialogTitle>
        <DialogDescription>Create a new workflow project</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4 max-h-[60vh] overflow-y-auto">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="name">Name<RequiredMark /></Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="My AI Project"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description (optional)</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe what this project is for..."
            className="min-h-[80px]"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="create-group">Group</Label>
          <Select value={groupId} onValueChange={setGroupId}>
            <SelectTrigger id="create-group">
              <SelectValue placeholder="Select a group" />
            </SelectTrigger>
            <SelectContent>
              {groups.map((g) => (
                <SelectItem key={g.id} value={g.id}>
                  {g.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            Governance container. Defaults to <code>Default</code>; the group can be changed later.
          </p>
        </div>

        <ServiceTypesField
          idPrefix="create"
          value={allowedServiceTypes}
          onChange={setAllowedServiceTypes}
        />

        {/* Default Workspace Repository */}
        <div className="border-t pt-4 mt-4">
          <div className="flex items-center gap-2 mb-3">
            <GitBranch className="h-4 w-4 text-muted-foreground" />
            <Label className="text-sm font-medium">Default Workspace Repository (optional)</Label>
          </div>
          <div className="space-y-3">
            <div className="space-y-2">
              <Label htmlFor="workspaceRepoUrl" className="text-sm">Repository URL</Label>
              <Input
                id="workspaceRepoUrl"
                value={workspaceRepoUrl}
                onChange={(e) => setWorkspaceRepoUrl(e.target.value)}
                placeholder="https://github.com/org/repo.git"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="workspaceRepoBranch" className="text-sm">Branch</Label>
              <Input
                id="workspaceRepoBranch"
                value={workspaceRepoBranch}
                onChange={(e) => setWorkspaceRepoBranch(e.target.value)}
                placeholder="main"
              />
            </div>
            <WorkspaceCredentialSelect
              value={workspaceCredentialSecretId}
              onChange={setWorkspaceCredentialSecretId}
              idPrefix="create"
            />
          </div>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Creating...' : 'Create Project'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditProjectFormProps {
  project: Project
  onSubmit: (data: UpdateProjectRequest) => void
  isLoading: boolean
  error?: string
}

function EditProjectForm({ project, onSubmit, isLoading, error }: EditProjectFormProps) {
  const [name, setName] = useState(project.name)
  const [description, setDescription] = useState(project.description || '')
  const [status, setStatus] = useState(project.status)
  const [allowedServiceTypes, setAllowedServiceTypes] = useState<string[]>(
    project.allowedServiceTypes ?? ['WORKFLOW', 'CONVERSATIONAL'],
  )
  const [workspaceRepoUrl, setWorkspaceRepoUrl] = useState(project.workspaceRepoUrl || '')
  const [workspaceRepoBranch, setWorkspaceRepoBranch] = useState(project.workspaceRepoBranch || 'main')
  const [workspaceCredentialSecretId, setWorkspaceCredentialSecretId] = useState(
    project.workspaceCredentialSecretId || ''
  )

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      status,
      allowedServiceTypes,
      workspaceRepoUrl: workspaceRepoUrl || undefined,
      workspaceRepoBranch: workspaceRepoBranch || undefined,
      workspaceCredentialSecretId: workspaceCredentialSecretId || '',
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Project</DialogTitle>
        <DialogDescription>Update project details</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4 max-h-[60vh] overflow-y-auto">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
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
            className="min-h-[80px]"
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

        <ServiceTypesField
          idPrefix="edit"
          value={allowedServiceTypes}
          onChange={setAllowedServiceTypes}
        />

        {/* Default Workspace Repository */}
        <div className="border-t pt-4 mt-4">
          <div className="flex items-center gap-2 mb-3">
            <GitBranch className="h-4 w-4 text-muted-foreground" />
            <Label className="text-sm font-medium">Default Workspace Repository</Label>
          </div>
          <div className="space-y-3">
            <div className="space-y-2">
              <Label htmlFor="edit-workspaceRepoUrl" className="text-sm">Repository URL</Label>
              <Input
                id="edit-workspaceRepoUrl"
                value={workspaceRepoUrl}
                onChange={(e) => setWorkspaceRepoUrl(e.target.value)}
                placeholder="https://github.com/org/repo.git"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="edit-workspaceRepoBranch" className="text-sm">Branch</Label>
              <Input
                id="edit-workspaceRepoBranch"
                value={workspaceRepoBranch}
                onChange={(e) => setWorkspaceRepoBranch(e.target.value)}
                placeholder="main"
              />
            </div>
            <WorkspaceCredentialSelect
              projectId={project.id}
              value={workspaceCredentialSecretId}
              onChange={setWorkspaceCredentialSecretId}
              idPrefix="edit"
            />
          </div>
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

interface MoveProjectGroupFormProps {
  project: Project
  groups: Group[]
  onSubmit: (groupId: string) => void
  isLoading: boolean
  error?: string
}

function MoveProjectGroupForm({
  project,
  groups,
  onSubmit,
  isLoading,
  error,
}: MoveProjectGroupFormProps) {
  const [groupId, setGroupId] = useState(project.groupId)

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit(groupId)
      }}
    >
      <DialogHeader>
        <DialogTitle>Move project to another group</DialogTitle>
        <DialogDescription>
          Changes the governance container for <strong>{project.name}</strong>. Quotas and group-scoped roles
          on the new group apply immediately.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="move-group">Target group</Label>
          <Select value={groupId} onValueChange={setGroupId}>
            <SelectTrigger id="move-group">
              <SelectValue placeholder="Select a group" />
            </SelectTrigger>
            <SelectContent>
              {groups.map((g) => (
                <SelectItem key={g.id} value={g.id}>
                  {g.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading || groupId === project.groupId}>
          {isLoading ? 'Moving...' : 'Move'}
        </Button>
      </DialogFooter>
    </form>
  )
}