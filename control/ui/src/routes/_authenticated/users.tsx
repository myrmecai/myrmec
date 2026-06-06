import { createFileRoute } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  authProvidersApi,
  usersApi,
  type User,
  type UserRole,
  type AuthProvider,
  type CreateUserRequest,
  type UpdateUserRequest,
} from '@/lib/api'
import {
  canEditCoreUserFields,
  canSetPasswordForUser,
  requiresPasswordForProvider,
} from '@/lib/user-auth-rules'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Pencil, Trash2, Shield, UserCheck, UserX } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/users')({
  component: UsersPage,
})

// All roles assignable at SYSTEM scope. PROJECT_OWNER is intentionally excluded
// (PROJECT scope only) and is granted from the project members page.
type SystemRoleSel =
  | 'PLATFORM_ADMIN'
  | 'ORG_ADMIN'
  | 'EDITOR'
  | 'VIEWER'
  | 'BUDGET_OWNER'
  | 'APPROVER'
  | 'AUDITOR'

const SYSTEM_ROLE_OPTIONS: { value: SystemRoleSel; label: string }[] = [
  { value: 'PLATFORM_ADMIN', label: 'PLATFORM_ADMIN — platform/tech operations' },
  { value: 'ORG_ADMIN', label: 'ORG_ADMIN — governance (groups, members, audit)' },
  { value: 'EDITOR', label: 'EDITOR — global create/modify (implies VIEWER)' },
  { value: 'VIEWER', label: 'VIEWER — global read-only' },
  { value: 'BUDGET_OWNER', label: 'BUDGET_OWNER — manages quotas' },
  { value: 'APPROVER', label: 'APPROVER — approves runs (implies VIEWER)' },
  { value: 'AUDITOR', label: 'AUDITOR — read audit + costs' },
]

function isSystemRoleSel(value: string): value is SystemRoleSel {
  return SYSTEM_ROLE_OPTIONS.some((opt) => opt.value === value)
}

function getCurrentSystemRoles(user: User): Set<SystemRoleSel> {
  const result = new Set<SystemRoleSel>()
  for (const role of user.roles ?? []) {
    if (role.scopeType === 'SYSTEM' && isSystemRoleSel(role.role)) {
      result.add(role.role)
    }
  }
  return result
}

async function syncSystemRoles(
  userId: string,
  existingRoles: UserRole[] | undefined,
  targetRoles: Set<SystemRoleSel>
) {
  const systemRoles = (existingRoles ?? []).filter((role) => role.scopeType === 'SYSTEM')

  // Remove every SYSTEM-scope row that is not in the new selection.
  for (const role of systemRoles) {
    if (!isSystemRoleSel(role.role) || !targetRoles.has(role.role)) {
      await usersApi.removeRole(userId, role.id)
    }
  }

  // Add any newly selected role not already granted.
  for (const role of targetRoles) {
    if (!systemRoles.some((existing) => existing.role === role)) {
      await usersApi.assignRole(userId, { role, scopeType: 'SYSTEM' })
    }
  }
}

function UsersPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editUser, setEditUser] = useState<User | null>(null)

  const { data: users, isLoading, error } = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.list,
  })

  const { data: providers = [], isLoading: isProvidersLoading, error: providersError } = useQuery({
    queryKey: ['auth-providers'],
    queryFn: authProvidersApi.list,
  })

  const createMutation = useMutation({
    mutationFn: async ({
      user,
      roles,
    }: {
      user: CreateUserRequest
      roles: Set<SystemRoleSel>
    }) => {
      const createdUser = await usersApi.create(user)
      for (const role of roles) {
        await usersApi.assignRole(createdUser.id, { role, scopeType: 'SYSTEM' })
      }
      return createdUser
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      setCreateOpen(false)
    },
  })

  const saveMutation = useMutation({
    mutationFn: async ({
      id,
      user,
      data,
      roles,
      newPassword,
    }: {
      id: string
      user: User
      data: UpdateUserRequest
      roles: Set<SystemRoleSel>
      newPassword?: string
    }) => {
      await usersApi.update(id, data)
      if (newPassword?.trim()) {
        await usersApi.updatePassword(id, { newPassword: newPassword.trim() })
      }
      await syncSystemRoles(id, user.roles, roles)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      setEditUser(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: usersApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
    },
  })

  if (isLoading || isProvidersLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading users and providers...</div>
      </div>
    )
  }

  if (error || providersError) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load users or providers</div>
      </div>
    )
  }

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Users</h1>
          <p className="text-muted-foreground">Manage platform users and roles</p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              Add User
            </Button>
          </DialogTrigger>
          <DialogContent>
            <CreateUserForm
              providers={providers.filter((provider) => provider.isEnabled)}
              onSubmit={(user, roles) => createMutation.mutate({ user, roles })}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Users</CardTitle>
          <CardDescription>{users?.length || 0} users registered</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Provider</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users?.map((user) => (
                <TableRow key={user.id}>
                  <TableCell className="font-medium">
                    {user.name}
                    {user.isSystem && (
                      <span className="ml-2 inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium bg-amber-100 text-amber-800">
                        System
                      </span>
                    )}
                  </TableCell>
                  <TableCell>{user.email}</TableCell>
                  <TableCell>
                    <span className="inline-flex items-center px-2 py-1 rounded-full text-xs font-medium bg-secondary">
                      {user.providerCode}
                    </span>
                  </TableCell>
                  <TableCell>
                    {user.isActive ? (
                      <span className="inline-flex items-center gap-1 text-green-600">
                        <UserCheck className="h-4 w-4" />
                        Active
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-muted-foreground">
                        <UserX className="h-4 w-4" />
                        Inactive
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {user.roles?.map((role) => (
                        <span
                          key={role.id}
                          className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-primary/10 text-primary"
                        >
                          <Shield className="h-3 w-3" />
                          {role.role}
                          {role.projectId && ` (project)`}
                        </span>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditUser(user)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      {user.isSystem ? (
                        <span className="text-xs text-muted-foreground">Protected</span>
                      ) : (
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => {
                            if (confirm('Are you sure you want to delete this user?')) {
                              deleteMutation.mutate(user.id)
                            }
                          }}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {users?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground">
                    No users found
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Edit User Dialog */}
      <Dialog open={!!editUser} onOpenChange={(open) => !open && setEditUser(null)}>
        <DialogContent>
          {editUser && (
            <EditUserForm
              user={editUser}
              providers={providers.filter((provider) => provider.isEnabled || provider.code === editUser.providerCode)}
              onSubmit={(data, roles, newPassword) =>
                saveMutation.mutate({ id: editUser.id, user: editUser, data, roles, newPassword })
              }
              isLoading={saveMutation.isPending}
              error={saveMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface CreateUserFormProps {
  providers: AuthProvider[]
  onSubmit: (data: CreateUserRequest, roles: Set<SystemRoleSel>) => void
  isLoading: boolean
  error?: string
}

function CreateUserForm({ providers, onSubmit, isLoading, error }: CreateUserFormProps) {
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [providerCode, setProviderCode] = useState('')
  const [roles, setRoles] = useState<Set<SystemRoleSel>>(() => new Set())

  const toggleRole = (role: SystemRoleSel) => {
    setRoles((prev) => {
      const next = new Set(prev)
      if (next.has(role)) next.delete(role)
      else next.add(role)
      return next
    })
  }

  useEffect(() => {
    if (!providerCode && providers.length > 0) {
      setProviderCode(providers[0].code)
    }
  }, [providerCode, providers])

  const requiresPassword = requiresPasswordForProvider(providerCode)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const data: CreateUserRequest = { name, email, providerCode }
    if (requiresPassword) {
      data.password = password
    }
    onSubmit(data, roles)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Add User</DialogTitle>
        <DialogDescription>Create a new user account</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
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
            placeholder="John Doe"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="john@example.com"
            required
          />
        </div>
        <div className="space-y-2">
          <Label>Roles</Label>
          <div className="rounded-md border p-3 space-y-2">
            {SYSTEM_ROLE_OPTIONS.map((opt) => (
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
            Select one or more system-wide roles. Leave all unchecked to create the user
            with no global role; per-project access is managed on each project. PROJECT_OWNER
            is project-scoped and not available here.
          </p>
        </div>
        <div className="space-y-2">
          <Label>Provider</Label>
          {providers.length === 0 && (

            <p className="text-xs text-muted-foreground">
              No enabled authentication providers are available.
            </p>
          )}
          <Select value={providerCode} onValueChange={setProviderCode}>
            <SelectTrigger>
              <SelectValue placeholder="Select provider" />
            </SelectTrigger>
            <SelectContent>
              {providers.map((provider) => (
                <SelectItem key={provider.code} value={provider.code}>
                  {provider.name} ({provider.code})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {requiresPassword && (
          <div className="space-y-2">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Min 8 chars, letter + digit + special"
              required
            />
          </div>
        )}
        {!requiresPassword && (
          <p className="text-xs text-muted-foreground">
            External users are created without local passwords.
          </p>
        )}
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading || !providerCode}>
          {isLoading ? 'Creating...' : 'Create User'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditUserFormProps {
  user: User
  providers: AuthProvider[]
  onSubmit: (data: UpdateUserRequest, roles: Set<SystemRoleSel>, newPassword?: string) => void
  isLoading: boolean
  error?: string
}

function EditUserForm({ user, providers, onSubmit, isLoading, error }: EditUserFormProps) {
  const [name, setName] = useState(user.name)
  const [providerCode, setProviderCode] = useState(user.providerCode)
  const [roles, setRoles] = useState<Set<SystemRoleSel>>(() => getCurrentSystemRoles(user))
  const [password, setPassword] = useState('')
  const [isActive, setIsActive] = useState(user.isActive)
  const canEditCoreFields = canEditCoreUserFields(user)
  const canSetPassword = canSetPasswordForUser(providerCode)

  const toggleRole = (role: SystemRoleSel) => {
    if (!canEditCoreFields) return
    setRoles((prev) => {
      const next = new Set(prev)
      if (next.has(role)) next.delete(role)
      else next.add(role)
      return next
    })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const data: UpdateUserRequest = canEditCoreFields
      ? { name, providerCode, isActive }
      : {}
    onSubmit(data, roles, password || undefined)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit User</DialogTitle>
        <DialogDescription>Update user information</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
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
            disabled={!canEditCoreFields}
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-email">Email</Label>
          <Input id="edit-email" value={user.email} disabled />
          <p className="text-xs text-muted-foreground">Email cannot be changed</p>
        </div>
        <div className="space-y-2">
          <Label>Roles</Label>
          <div className="rounded-md border p-3 space-y-2">
            {SYSTEM_ROLE_OPTIONS.map((opt) => (
              <label
                key={opt.value}
                className={`flex items-start gap-2 text-sm select-none ${
                  canEditCoreFields ? 'cursor-pointer' : 'opacity-60 cursor-not-allowed'
                }`}
              >
                <input
                  type="checkbox"
                  className="mt-0.5 h-4 w-4 rounded border-input"
                  checked={roles.has(opt.value)}
                  onChange={() => toggleRole(opt.value)}
                  disabled={!canEditCoreFields}
                />
                <span>{opt.label}</span>
              </label>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            Select one or more system-wide roles. Unchecking a role removes it. PROJECT_OWNER
            and other project-scoped grants are managed on each project's members page.
          </p>
          {user.isSystem && (
            <p className="text-xs text-muted-foreground">System admin roles are immutable.</p>
          )}
        </div>
        <div className="space-y-2">
          <Label>Provider</Label>
          <Select
            value={providerCode}
            onValueChange={setProviderCode}
            disabled={!canEditCoreFields}
          >
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {providers.map((provider) => (
                <SelectItem key={provider.code} value={provider.code}>
                  {provider.name} ({provider.code})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {user.isSystem && (
            <p className="text-xs text-muted-foreground">System admin provider is immutable.</p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-password">New Password (optional)</Label>
          <Input
            id="edit-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={canSetPassword ? 'Leave blank to keep current' : 'Password managed externally'}
            disabled={!canSetPassword}
          />
          {!canSetPassword && (
            <p className="text-xs text-muted-foreground">Password is only available for LOCAL users.</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="edit-active"
            checked={isActive}
            onChange={(e) => setIsActive(e.target.checked)}
            className="h-4 w-4"
            disabled={!canEditCoreFields}
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
