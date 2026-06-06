import { createFileRoute } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { groupsApi, type Group, type CreateGroupRequest } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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
import { Building2, Plus, Trash2 } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/admin/groups/')({
  component: GroupsPage,
})

function GroupsPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: groups, isLoading, error } = useQuery({
    queryKey: ['groups'],
    queryFn: groupsApi.list,
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateGroupRequest) => groupsApi.create(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
      setCreateOpen(false)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => groupsApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['groups'] }),
  })

  return (
    <div className="container py-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-2">
            <Building2 className="h-7 w-7" />
            Groups
          </h1>
          <p className="text-muted-foreground">
            Governance containers above projects. Anchors group-level budgets and policy.
          </p>
        </div>
        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Group
            </Button>
          </DialogTrigger>
          <CreateGroupDialog
            onSubmit={(data) => createMutation.mutate(data)}
            isLoading={createMutation.isPending}
            error={createMutation.error}
          />
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All groups</CardTitle>
          <CardDescription>
            The seeded <code>Default</code> group cannot be deleted.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
          {error && (
            <p className="text-sm text-destructive">
              Failed to load groups: {(error as Error).message}
            </p>
          )}
          {groups && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Description</TableHead>
                  <TableHead className="w-[80px]" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {groups.map((g: Group) => (
                  <TableRow key={g.id}>
                    <TableCell className="font-medium">{g.name}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {g.description ?? '—'}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          if (window.confirm(`Delete group "${g.name}"?`)) {
                            deleteMutation.mutate(g.id)
                          }
                        }}
                        disabled={g.name === 'Default'}
                        title={g.name === 'Default' ? 'Default group is protected' : 'Delete'}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
                {groups.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={3} className="text-center text-muted-foreground">
                      No groups yet.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function CreateGroupDialog({
  onSubmit,
  isLoading,
  error,
}: {
  onSubmit: (data: CreateGroupRequest) => void
  isLoading: boolean
  error: unknown
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>New group</DialogTitle>
        <DialogDescription>
          A new governance container. You can attach projects to it after creation.
        </DialogDescription>
      </DialogHeader>
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          onSubmit({ name: name.trim(), description: description.trim() || undefined })
        }}
      >
        <div className="space-y-2">
          <Label htmlFor="group-name">Name</Label>
          <Input
            id="group-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Engineering"
            required
            maxLength={200}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="group-description">Description</Label>
          <Textarea
            id="group-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Optional"
            maxLength={3000}
          />
        </div>
        {error != null && (
          <p className="text-sm text-destructive">{(error as Error).message}</p>
        )}
        <DialogFooter>
          <Button type="submit" disabled={!name.trim() || isLoading}>
            {isLoading ? 'Creating…' : 'Create'}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}
