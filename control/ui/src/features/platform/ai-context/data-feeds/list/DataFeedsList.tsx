// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  dataFeedApi,
  knowledgeProviderApi,
  type CreateDataFeedRequest,
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
} from '@/components/ui/dialog'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Plus, AlertCircle, RefreshCw } from 'lucide-react'
import { SYNC_STATUS_COLORS } from '../shared/constants'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'
import { Scope, EntityStatus } from '@/lib/domain-constants'

export function DataFeedsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: feeds, isLoading, error } = useQuery({
    queryKey: ['data-feeds'],
    queryFn: dataFeedApi.list,
  })

  const createMutation = useMutation({
    mutationFn: dataFeedApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['data-feeds'] })
      setCreateOpen(false)
    },
  })

  const syncMutation = useMutation({
    mutationFn: dataFeedApi.triggerSync,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['data-feeds'] }),
  })

  const disableMutation = useMutation({
    mutationFn: dataFeedApi.disable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['data-feeds'] }),
  })

  const columns: ColumnDef<NonNullable<typeof feeds>[number]>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <div className="font-medium">
          <Link to="/platform/ai-context/data-feeds/$id" params={{ id: row.original.id }} className="hover:underline">
            {row.original.name}
          </Link>
          {row.original.description && (
            <span className="text-xs text-muted-foreground block">{row.original.description}</span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'datasetName',
      header: 'Dataset',
      cell: ({ row }) => <Badge variant="outline">{row.original.datasetName}</Badge>,
    },
    {
      accessorKey: 'syncStatus',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Sync Status" />,
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <div className={`h-2 w-2 rounded-full ${SYNC_STATUS_COLORS[row.original.syncStatus] || 'bg-gray-400'}`} />
          <span className="text-sm">{row.original.syncStatus}</span>
          {row.original.errorMessage && (
            <span className="text-xs text-destructive">{row.original.errorMessage}</span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'lastSyncAt',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Last Sync" />,
      cell: ({ row }) => (
        <span className="text-sm text-muted-foreground">
          {row.original.lastSyncAt ? new Date(row.original.lastSyncAt).toLocaleString() : '—'}
        </span>
      ),
    },
    {
      accessorKey: 'chunkCount',
      header: 'Chunks',
      cell: ({ row }) => (
        <span className="text-sm">{row.original.chunkCount != null ? row.original.chunkCount : '—'}</span>
      ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const feed = row.original
        return (
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => syncMutation.mutate(feed.id)}
              disabled={syncMutation.isPending}
            >
              <RefreshCw className="h-3 w-3 mr-1" />
              Sync
            </Button>
            {feed.status === EntityStatus.ACTIVE && (
              <Button size="sm" variant="outline" onClick={() => disableMutation.mutate(feed.id)}>
                Disable
              </Button>
            )}
          </div>
        )
      },
    },
  ], [syncMutation, disableMutation])

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load data feeds</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Data Feeds</h1>
          <p className="text-muted-foreground">Scheduled data ingestion feeds from knowledge providers</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Feed
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Feeds</CardTitle>
          <CardDescription>Data feeds with sync scheduling and status tracking</CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={feeds ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      <CreateDataFeedDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateDataFeedDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateDataFeedRequest) => void
  isPending: boolean
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [datasetName, setDatasetName] = useState('')
  const [syncSchedule, setSyncSchedule] = useState('')

  const { data: providers } = useQuery({
    queryKey: ['knowledge-providers'],
    queryFn: () => knowledgeProviderApi.list(),
  })

  const publishedProviders = providers?.filter((p) => p.status === EntityStatus.ACTIVE) || []

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!publishedProviders.length) return
    onCreate({
      scope: Scope.ORGANIZATION,
      name,
      description: description || undefined,
      providerVersionId: publishedProviders[0]?.currentVersionId || '',
      datasetName,
      syncSchedule: syncSchedule || undefined,
    })
    setName('')
    setDescription('')
    setDatasetName('')
    setSyncSchedule('')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Data Feed</DialogTitle>
          <DialogDescription>Define a new data feed for scheduled ingestion</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Daily Docs Sync" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Brief description" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="datasetName">Dataset Name</Label>
            <Input id="datasetName" value={datasetName} onChange={(e) => setDatasetName(e.target.value)} placeholder="e.g., product-docs" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="syncSchedule">Sync Schedule (cron)</Label>
            <Input id="syncSchedule" value={syncSchedule} onChange={(e) => setSyncSchedule(e.target.value)} placeholder="e.g., 0 2 * * * (daily at 2am)" />
          </div>
          {publishedProviders.length === 0 && (
            <p className="text-sm text-yellow-600">No active knowledge providers. Create and publish a provider first.</p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending || !name || !datasetName || publishedProviders.length === 0}>
              {isPending ? 'Creating...' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}