// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import {
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
import { Plus, Shield, Trash2 } from 'lucide-react'
import { dialogService } from '@/services/dialog-service'

/** Grants for one user grouped into a single row (mirrors the Users list). */
interface GroupedMember {
  userId: string
  email: string
  name: string | null
  isActive: boolean
  /** One entry per granted role — each is a separate user_roles row. */
  grants: ProjectMember[]
}

export function ProjectMembers({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const { isOrgAdmin, hasProjectRole } = useAuth()

  // Member management is owner-level (mirrors @projectAccess.canOwn):
  // project-scoped PROJECT_OWNER or system-wide ORG_ADMIN. PLATFORM_ADMIN has no
  // data access (separation of duties) and an EDITOR cannot grant roles.
  const canEdit = useMemo(() => {
    return isOrgAdmin || hasProjectRole(projectId, 'PROJECT_OWNER')
  }, [isOrgAdmin, hasProjectRole, projectId])

  const [addOpen, setAddOpen] = useState(false)

  const {
    data: members,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['project-members', projectId],
    queryFn: () => projectMembersApi.list(projectId),
  })

  const projectMembers = members?.projectMembers ?? []

  // The backend returns one row per role grant; group them so each member
  // occupies a single row with all their roles as chips (same as Users list).
  const groupedMembers = useMemo(() => {
    const byUser = new Map<string, GroupedMember>()
    for (const m of projectMembers) {
      const existing = byUser.get(m.userId)
      if (existing) {
        existing.grants.push(m)
      } else {
        byUser.set(m.userId, {
          userId: m.userId,
          email: m.email,
          name: m.name,
          isActive: m.isActive,
          grants: [m],
        })
      }
    }
    return Array.from(byUser.values())
  }, [projectMembers])

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
    // A grouped member owns one user_roles row per granted role — removing
    // the member removes every grant.
    mutationFn: (m: GroupedMember) =>
      Promise.all(
        m.grants.map((g) =>
          projectMembersApi.remove(projectId, g.userId, g.projectRoleId),
        ),
      ),
    onSuccess: invalidate,
  })

  if (isLoading) {
    return <div className="text-muted-foreground">Loading members...</div>
  }

  if (error) {
    return <div className="text-destructive">Failed to load members</div>
  }

  const systemWideUsers = members?.systemWideUsers ?? []

  return (
    <div>
      <Card className="mb-8">
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>Project Members</CardTitle>
            <CardDescription>
              {groupedMembers.length} user(s) with explicit access to this project.
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
              {groupedMembers.map((m) => (
                <TableRow key={m.userId}>
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
                    <div className="flex flex-wrap gap-1">
                      {m.grants.map((g) => (
                        <span
                          key={g.projectRoleId}
                          className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-primary/10 text-primary"
                        >
                          <Shield className="h-3 w-3" />
                          {g.role}
                        </span>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell className="text-xs">
                    {Array.from(
                      new Set(
                        m.grants.map((g) => g.grantedByEmail).filter(Boolean),
                      ),
                    ).join(', ') || '-'}
                  </TableCell>
                  <TableCell className="text-xs">
                    {new Date(
                      m.grants
                        .map((g) => new Date(g.createdAt).getTime())
                        .sort((a, b) => a - b)[0],
                    ).toLocaleString()}
                  </TableCell>
                  {canEdit && (
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={async () => {
                          const roleNames = m.grants.map((g) => g.role).join(', ')
                          const confirmed = await dialogService.showConfirmDialog({
                            title: 'Remove Member',
                            message: `Remove ${m.email} from this project? All of their role grants (${roleNames}) will be removed.`,
                            severity: 'warning',
                            type: 'warning',
                            confirmLabel: 'Remove',
                            cancelLabel: 'Cancel',
                          })
                          if (confirmed) removeMutation.mutate(m)
                        }}
                        title="Remove"
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {groupedMembers.length === 0 && (
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

  const { data: candidates, isLoading: candidatesLoading, error: candidatesError } = useQuery({
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
          {candidatesError ? (
            <p className="text-sm text-destructive">
              Failed to load eligible users: {candidatesError.message}
            </p>
          ) : (
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
          )}
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