import { createFileRoute, Outlet, useMatch, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { projectsApi, projectSecretsApi, globalSecretsApi, groupsApi, type Project, type CreateProjectRequest, type UpdateProjectRequest, type Secret, type CredentialType, type Group } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Pencil, Trash2, FolderOpen, FolderX, BookOpen, GitBranch, KeyRound, Users, Building2, ArrowRightLeft } from 'lucide-react'

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

export const Route = createFileRoute('/_authenticated/projects')({
  component: ProjectsLayout,
})

function ProjectsLayout() {
  const knowledgeMatch = useMatch({
    from: '/_authenticated/projects/$projectId/knowledge',
    shouldThrow: false,
  })
  const secretsMatch = useMatch({
    from: '/_authenticated/projects/$projectId/secrets',
    shouldThrow: false,
  })
  const membersMatch = useMatch({
    from: '/_authenticated/projects/$projectId/members',
    shouldThrow: false,
  })

  if (knowledgeMatch || secretsMatch || membersMatch) {
    return <Outlet />
  }

  return <ProjectsPage />
}

function ProjectsPage() {
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
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Group</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {projects?.map((project) => {
                const canTransfer =
                  isPlatformAdmin ||
                  isOrgAdmin ||
                  hasProjectRole(project.id, 'PROJECT_OWNER')
                return (
                <TableRow key={project.id}>
                  <TableCell className="font-medium">{project.name}</TableCell>
                  <TableCell>
                    <span className="inline-flex items-center gap-1 text-sm text-muted-foreground">
                      <Building2 className="h-3.5 w-3.5" />
                      {groupById.get(project.groupId)?.name ?? '—'}
                    </span>
                  </TableCell>
                  <TableCell className="max-w-[300px] truncate">
                    {project.description || <span className="text-muted-foreground">No description</span>}
                  </TableCell>
                  <TableCell>
                    {project.status === 'ACTIVE' ? (
                      <span className="inline-flex items-center gap-1 text-green-600">
                        <FolderOpen className="h-4 w-4" />
                        Active
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-muted-foreground">
                        <FolderX className="h-4 w-4" />
                        Inactive
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    {new Date(project.createdAt).toLocaleDateString()}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Knowledge"
                        onClick={() => navigate({
                          to: '/projects/$projectId/knowledge',
                          params: { projectId: project.id },
                        })}
                      >
                        <BookOpen className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Secrets"
                        onClick={() => navigate({
                          to: '/projects/$projectId/secrets',
                          params: { projectId: project.id },
                        })}
                      >
                        <KeyRound className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        title="Members"
                        onClick={() => navigate({
                          to: '/projects/$projectId/members',
                          params: { projectId: project.id },
                        })}
                      >
                        <Users className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditProject(project)}
                        title="Edit"
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      {canTransfer && (
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => setMoveProject(project)}
                          title="Move to group"
                        >
                          <ArrowRightLeft className="h-4 w-4" />
                        </Button>
                      )}
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          if (confirm('Are you sure you want to delete this project?')) {
                            deleteMutation.mutate(project.id)
                          }
                        }}
                        title="Delete"
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
                )
              })}
              {projects?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground">
                    No projects yet. Create your first project to get started.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
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
  const [workspaceRepoUrl, setWorkspaceRepoUrl] = useState('')
  const [workspaceRepoBranch, setWorkspaceRepoBranch] = useState('main')
  const [workspaceCredentialSecretId, setWorkspaceCredentialSecretId] = useState('')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      description: description || undefined,
      groupId: groupId || undefined,
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
          <Label htmlFor="name">Name</Label>
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