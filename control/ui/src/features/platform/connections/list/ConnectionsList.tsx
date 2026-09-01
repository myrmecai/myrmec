// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  connectionConfigApi,
  type ConnectionConfig,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
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
import { Scope, EntityStatus } from '@/lib/domain-constants'
import { CreateConnectionConfigWizard } from '../components/CreateConnectionConfigWizard'

export function ConnectionsList() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  const { data: configs, isLoading, error } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  const createMutation = useMutation({
    // The wizard handles create + draft + update + publish internally.
    // This mutation is just a redirect trigger after the wizard calls onCreated.
    // We keep it for API parity but it's a no-op — the wizard does everything.
    mutationFn: async (config: ConnectionConfig) => config,
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['connection-configs'] })
      setCreateOpen(false)
      setToastMessage('Connection Config created and published.')
      setTimeout(() => setToastMessage(null), 5000)
      navigate({ to: '/platform/connections/$id', params: { id: data.id }, search: { edit: false } })
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
    () => (configs ?? []).filter((c) => c.status !== EntityStatus.ARCHIVED),
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
          <Link to="/platform/connections/$id" params={{ id: row.original.id }} search={{ edit: false }} className="hover:underline">
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
                <Link to="/platform/connections/$id" params={{ id: config.id }} search={{ edit: false }}>
                  <Eye className="h-4 w-4 mr-2" /> View
                </Link>
              </DropdownMenuItem>
              {config.status === EntityStatus.ACTIVE && (
                <DropdownMenuItem onClick={async () => {
                  const confirmed = await dialogService.showConfirmDialog({
                    title: 'Disable Connection Config',
                    message: `Disable "${config.name}"? Services using this connection will no longer be able to resolve its credentials until it is re-enabled.`,
                    severity: 'warning',
                    type: 'warning',
                    confirmLabel: 'Disable',
                    cancelLabel: 'Cancel',
                  })
                  if (confirmed) disableMutation.mutate(config.id)
                }}>
                  <PowerOff className="h-4 w-4 mr-2" /> Disable
                </DropdownMenuItem>
              )}
              {config.status === EntityStatus.DISABLED && (
                <DropdownMenuItem onClick={async () => {
                  const confirmed = await dialogService.showConfirmDialog({
                    title: 'Enable Connection Config',
                    message: `Re-enable "${config.name}"? Services will immediately be able to resolve its credentials again.`,
                    severity: 'info',
                    type: 'warning',
                    confirmLabel: 'Enable',
                    cancelLabel: 'Cancel',
                  })
                  if (confirmed) reenableMutation.mutate(config.id)
                }}>
                  <Power className="h-4 w-4 mr-2" /> Enable
                </DropdownMenuItem>
              )}
              {config.status !== EntityStatus.ARCHIVED && (
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
                  disabled={config.status === EntityStatus.ACTIVE}
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

      <CreateConnectionConfigWizard
        open={createOpen}
        onOpenChange={setCreateOpen}
        defaultScope={Scope.ORGANIZATION}
        onCreated={(config) => createMutation.mutate(config)}
      />
    </div>
    </ContentAreaLayout>
  )
}