import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import {
  projectsApi,
  projectMembersApi,
  type ProjectMember,
  type ProjectMemberRole,
  type SystemWideUser,
} from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Plus, Trash2, ChevronLeft, Users } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/projects/$projectId/members')({
  component: ProjectMembersPage,
})

function ProjectMembersPage() {
  const { projectId } = Route.useParams()
  const queryClient = useQueryClient()
  const { isPlatformAdmin, isOrgAdmin, hasProjectRole } = useAuth()

  const canEdit = useMemo(() => {
    return isPlatformAdmin || isOrgAdmin || hasProjectRole(projectId, 'EDITOR')
  }, [isPlatformAdmin, isOrgAdmin, hasProjectRole, projectId])

  const [addOpen, setAddOpen] = useState(false)

  const { data: project } = useQuery({
    queryKey: ['projects', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const {
    data: members,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['project-members', projectId],
    queryFn: () => projectMembersApi.list(projectId),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['project-members', projectId] })
    queryClient.invalidateQueries({ queryKey: ['project-member-candidates', projectId] })
  }

  const assignMutation = useMutation({
    mutationFn: async (data: { userId: string; roles: ProjectMemberRole[] }) => {
      // Issue one POST per role; the backend treats each role as an independent grant.
      for (const role of data.roles) {
        await projectMembersApi.assign(projectId, { userId: data.userId, role })
      }
    },
    onSuccess: () => {
      invalidate()
      setAddOpen(false)
    },
  })

  const removeMutation = useMutation({
    mutationFn: (m: ProjectMember) =>
      projectMembersApi.remove(projectId, m.userId, m.projectRoleId),
    onSuccess: invalidate,
  })

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading members...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load members</div>
      </div>
    )
  }

  const projectMembers = members?.projectMembers ?? []
  const systemWideUsers = members?.systemWideUsers ?? []

  return (
    <div className="p-8">
      <div className="mb-4">
        <Link
          to="/projects"
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft className="h-4 w-4 mr-1" />
          Back to Projects
        </Link>
      </div>

      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-2">
            <Users className="h-7 w-7" />
            Project Members
          </h1>
          <p className="text-muted-foreground">
            {project?.name
              ? `Manage who can access project "${project.name}"`
              : 'Manage who can access this project'}
          </p>
        </div>
      </div>

      <Card className="mb-8">
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>Project Members</CardTitle>
            <CardDescription>
              {projectMembers.length} user(s) with explicit access to this project.
            </CardDescription>
          </div>
          {canEdit && (
            <Dialog open={addOpen} onOpenChange={setAddOpen}>
              <DialogTrigger asChild>
                <Button variant="outline">
                  <Plus className="h-4 w-4 mr-2" />
                  Add Member
                </Button>
              </DialogTrigger>
              <DialogContent>
                <AddMemberForm
                  projectId={projectId}
                  enabled={addOpen}
                  onSubmit={(data) => assignMutation.mutate(data)}
                  isLoading={assignMutation.isPending}
                  error={assignMutation.error?.message}
                />
              </DialogContent>
            </Dialog>
          )}
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Granted By</TableHead>
                <TableHead>Granted At</TableHead>
                {canEdit && <TableHead className="w-[80px]">Actions</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {projectMembers.map((m) => (
                <TableRow key={m.projectRoleId}>
                  <TableCell className="font-medium">
                    {m.email}
                    {!m.isActive && (
                      <Badge variant="outline" className="ml-2 text-xs">
                        inactive
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell>{m.name ?? '-'}</TableCell>
                  <TableCell>
                    <Badge variant={m.role === 'EDITOR' ? 'default' : 'secondary'}>
                      {m.role}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs">{m.grantedByEmail ?? '-'}</TableCell>
                  <TableCell className="text-xs">
                    {new Date(m.createdAt).toLocaleString()}
                  </TableCell>
                  {canEdit && (
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          if (confirm(`Remove ${m.email} from this project?`)) {
                            removeMutation.mutate(m)
                          }
                        }}
                        title="Remove"
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {projectMembers.length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={canEdit ? 6 : 5}
                    className="text-center text-muted-foreground py-8"
                  >
                    No explicit members yet.
                    {canEdit && ' Click "Add Member" to grant access.'}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Global Users</CardTitle>
          <CardDescription>
            Users with system-wide roles who are not yet members of this project.
            System admins implicitly have access; editors and viewers can be added
            via Add Member.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>System Role</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {systemWideUsers.map((u: SystemWideUser) => (
                <TableRow key={u.userId}>
                  <TableCell className="font-medium">{u.email}</TableCell>
                  <TableCell>{u.name ?? '-'}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{u.role}</Badge>
                  </TableCell>
                </TableRow>
              ))}
              {systemWideUsers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} className="text-center text-muted-foreground py-8">
                    No global users to show.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}

function AddMemberForm({
  projectId,
  enabled,
  onSubmit,
  isLoading,
  error,
}: {
  projectId: string
  enabled: boolean
  onSubmit: (data: { userId: string; roles: ProjectMemberRole[] }) => void
  isLoading: boolean
  error?: string
}) {
  const [userId, setUserId] = useState('')
  const [roles, setRoles] = useState<Set<ProjectMemberRole>>(
    () => new Set<ProjectMemberRole>(['VIEWER']),
  )

  const { data: candidates, isLoading: candidatesLoading } = useQuery({
    queryKey: ['project-member-candidates', projectId],
    queryFn: () => projectMembersApi.listCandidates(projectId),
    enabled,
  })

  const toggleRole = (role: ProjectMemberRole) => {
    setRoles((prev) => {
      const next = new Set(prev)
      if (next.has(role)) next.delete(role)
      else next.add(role)
      return next
    })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!userId || roles.size === 0) return
    onSubmit({ userId, roles: Array.from(roles) })
  }

  const roleOptions: { value: ProjectMemberRole; label: string }[] = [
    { value: 'VIEWER', label: 'VIEWER — read-only' },
    { value: 'EDITOR', label: 'EDITOR — create/modify (implies VIEWER)' },
    { value: 'PROJECT_OWNER', label: 'PROJECT_OWNER — full control (implies EDITOR + VIEWER)' },
    { value: 'APPROVER', label: 'APPROVER — approves runs (implies VIEWER)' },
    { value: 'BUDGET_OWNER', label: 'BUDGET_OWNER — manages quotas' },
    { value: 'AUDITOR', label: 'AUDITOR — read audit + costs' },
  ]

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Add Member</DialogTitle>
        <DialogDescription>
          Grant a user one or more roles on this project. Each selected role is added as
          a separate grant; existing roles for the user on this project are kept.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="add-member-user">User</Label>
          <Select value={userId} onValueChange={setUserId} disabled={candidatesLoading}>
            <SelectTrigger id="add-member-user">
              <SelectValue
                placeholder={candidatesLoading ? 'Loading users...' : 'Select a user'}
              />
            </SelectTrigger>
            <SelectContent>
              {(candidates ?? []).map((c) => (
                <SelectItem key={c.userId} value={c.userId}>
                  {c.email}
                  {c.name ? ` — ${c.name}` : ''}
                </SelectItem>
              ))}
              {!candidatesLoading && (candidates?.length ?? 0) === 0 && (
                <div className="px-2 py-1.5 text-xs text-muted-foreground">
                  No eligible users available
                </div>
              )}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label>Roles</Label>
          <div className="rounded-md border p-3 space-y-2">
            {roleOptions.map((opt) => (
              <label
                key={opt.value}
                className="flex items-start gap-2 text-sm cursor-pointer select-none"
              >
                <input
                  type="checkbox"
                  className="mt-0.5 h-4 w-4 rounded border-input"
                  checked={roles.has(opt.value)}
                  onChange={() => toggleRole(opt.value)}
                />
                <span>{opt.label}</span>
              </label>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            Select one or more roles. Each is granted independently and can be removed
            individually from the member list.
          </p>
        </div>

        {error && <div className="text-sm text-destructive">{error}</div>}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={!userId || roles.size === 0 || isLoading}>
          {isLoading
            ? 'Adding...'
            : roles.size > 1
              ? `Add Member (${roles.size} roles)`
              : 'Add Member'}
        </Button>
      </DialogFooter>
    </form>
  )
}
