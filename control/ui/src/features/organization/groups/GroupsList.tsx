// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import { groupsApi, type Group, type CreateGroupRequest } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
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
import { Building2, Plus, Trash2 } from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

export function GroupsList() {
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

  const columns: ColumnDef<Group>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => <span className="font-medium">{row.original.name}</span>,
    },
    {
      accessorKey: 'description',
      header: 'Description',
      cell: ({ row }) => (
        <span className="text-muted-foreground">{row.original.description ?? '—'}</span>
      ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const g = row.original
        return (
          <Button
            variant="ghost"
            size="icon"
            onClick={async () => {
              const confirmed = await dialogService.showConfirmDialog({
                title: 'Delete Group',
                message: `Delete group "${g.name}"? This cannot be undone.`,
                severity: 'error',
                type: 'warning',
                confirmLabel: 'Delete',
                cancelLabel: 'Cancel',
              })
              if (confirmed) deleteMutation.mutate(g.id)
            }}
            disabled={g.name === 'Default'}
            title={g.name === 'Default' ? 'Default group is protected' : 'Delete'}
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        )
      },
    },
  ], [deleteMutation])

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
          {error ? (
            <p className="text-sm text-destructive">
              Failed to load groups: {(error as Error).message}
            </p>
          ) : (
            <DataTable2
              columns={columns}
              data={groups ?? []}
              pagination={true}
              loading={isLoading}
              showRowSelection={false}
            />
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
          <Label htmlFor="group-name">Name<RequiredMark /></Label>
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