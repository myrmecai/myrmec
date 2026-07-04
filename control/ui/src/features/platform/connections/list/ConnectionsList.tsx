// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  connectionConfigApi,
  type ConnectionConfig,
  type ConnectionType,
  type CreateConnectionConfigRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
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
} from '@/components/ui/dialog'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Plus, AlertCircle, MoreVertical, Eye, Power, PowerOff, Archive, Trash2 } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'
import { TYPE_LABELS, STATUS_COLORS } from '../shared/constants'

export function ConnectionsList() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  const { data: configs, isLoading, error } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  const [createError, setCreateError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: async (data: CreateConnectionConfigRequest) => {
      // Create the config, then auto-create a Draft (Pattern 4 §Rule 4:
      // "Zone 2 Draft ready for editing")
      const config = await connectionConfigApi.create(data)
      await connectionConfigApi.createDraft(config.id)
      return config
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['connection-configs'] })
      setCreateOpen(false)
      setCreateError(null)
      // Show toast (Pattern 4 §Rule 5, Pattern 6)
      setToastMessage('Connection Config created — please complete and publish.')
      setTimeout(() => setToastMessage(null), 5000)
      // Navigate to detail page in Edit mode (Pattern 4 §Rule 3, 4)
      navigate({ to: '/platform/connections/$id', params: { id: data.id }, search: { edit: true } })
    },
    onError: (error: any) => {
      // Try to extract field-specific message from validation error details
      const details = error?.error?.details
      if (Array.isArray(details) && details.length > 0 && details[0]?.message) {
        setCreateError(details[0].message)
      } else {
        setCreateError(error?.error?.message ?? 'Failed to create connection config')
      }
    },
  })

  const disableMutation = useMutation({
    mutationFn: connectionConfigApi.disable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  const reenableMutation = useMutation({
    mutationFn: connectionConfigApi.reenable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  const archiveMutation = useMutation({
    mutationFn: connectionConfigApi.archive,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  const deleteMutation = useMutation({
    mutationFn: connectionConfigApi.delete,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  const filteredConfigs = useMemo(
    () => (configs ?? []).filter((c) => c.status !== 'ARCHIVED'),
    [configs],
  )

  const columns: ColumnDef<ConnectionConfig>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Name" />
      ),
      cell: ({ row }) => (
        <div className="font-medium">
          <Link to="/platform/connections/$id" params={{ id: row.original.id }} className="hover:underline">
            {row.original.name}
          </Link>
          {row.original.description && (
            <span className="text-xs text-muted-foreground block">{row.original.description}</span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'type',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Type" />
      ),
      cell: ({ row }) => (
        <Badge variant="outline">{TYPE_LABELS[row.original.type] || row.original.type}</Badge>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Status" />
      ),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[row.original.status] || 'bg-gray-400'}`} />
          <span className="text-sm">{row.original.status}</span>
        </div>
      ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const config = row.original
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0" aria-label="More actions">
                <MoreVertical className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem asChild>
                <Link to="/platform/connections/$id" params={{ id: config.id }}>
                  <Eye className="h-4 w-4 mr-2" /> View
                </Link>
              </DropdownMenuItem>
              {config.status === 'ACTIVE' && (
                <DropdownMenuItem onClick={() => disableMutation.mutate(config.id)}>
                  <PowerOff className="h-4 w-4 mr-2" /> Disable
                </DropdownMenuItem>
              )}
              {config.status === 'DISABLED' && (
                <DropdownMenuItem onClick={() => reenableMutation.mutate(config.id)}>
                  <Power className="h-4 w-4 mr-2" /> Enable
                </DropdownMenuItem>
              )}
              {config.status !== 'ARCHIVED' && (
                <DropdownMenuItem
                  onClick={async () => {
                    const confirmed = await dialogService.showConfirmDialog({
                      title: 'Archive Connection Config',
                      message: `Archive "${config.name}"? Archived configs are hidden from the list but can be restored.`,
                      severity: 'warning',
                      type: 'warning',
                      confirmLabel: 'Archive',
                      cancelLabel: 'Cancel',
                    })
                    if (confirmed) archiveMutation.mutate(config.id)
                  }}
                  disabled={config.status === 'ACTIVE'}
                >
                  <Archive className="h-4 w-4 mr-2" /> Archive
                </DropdownMenuItem>
              )}
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="text-destructive"
                onClick={async () => {
                  const confirmed = await dialogService.showConfirmDialog({
                    title: 'Delete Connection Config',
                    message: `Delete "${config.name}"? This cannot be undone.`,
                    severity: 'error',
                    type: 'warning',
                    confirmLabel: 'Delete',
                    cancelLabel: 'Cancel',
                  })
                  if (confirmed) deleteMutation.mutate(config.id)
                }}
              >
                <Trash2 className="h-4 w-4 mr-2" /> Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )
      },
    },
  ], [disableMutation, reenableMutation, archiveMutation, deleteMutation])

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load connection configs</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      {toastMessage && (
        <div
          className="fixed bottom-4 right-4 z-50 rounded-lg border bg-background p-4 shadow-lg"
          role="status"
          aria-live="polite"
        >
          <p className="text-sm">{toastMessage}</p>
        </div>
      )}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Connection Configs</h1>
          <p className="text-muted-foreground">Global connection definitions for GIT, HTTP, S3, and more</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Connection
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Connections</CardTitle>
          <CardDescription>Reusable connection definitions with versioned configs</CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={filteredConfigs}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      <CreateConnectionConfigDialog
        open={createOpen}
        onOpenChange={(v) => { setCreateOpen(v); if (v) setCreateError(null) }}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
        error={createError}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateConnectionConfigDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
  error,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateConnectionConfigRequest) => void
  isPending: boolean
  error: string | null
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<ConnectionType>('GIT')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'ORGANIZATION',
      name,
      description: description || undefined,
      type,
    })
    setName('')
    setDescription('')
    setType('GIT')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Connection Config</DialogTitle>
          <DialogDescription>Define a new global connection</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Company Git Repo" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Brief description" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="type">Connection Type</Label>
            <Select value={type} onValueChange={(v) => setType(v as ConnectionType)}>
              <SelectTrigger id="type"><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(TYPE_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {error && (
            <div className="flex items-center gap-2 text-sm text-destructive">
              <AlertCircle className="h-4 w-4" />
              <span>{error}</span>
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending || !name}>{isPending ? 'Creating...' : 'Create'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}