// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Plus, AlertCircle, RefreshCw } from 'lucide-react'
import { SYNC_STATUS_COLORS } from '../shared/constants'

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
          {feeds && feeds.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Dataset</TableHead>
                  <TableHead>Sync Status</TableHead>
                  <TableHead>Last Sync</TableHead>
                  <TableHead>Chunks</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {feeds.map((feed) => (
                  <TableRow key={feed.id}>
                    <TableCell className="font-medium">
                      <Link to="/platform/ai-context/data-feeds/$id" params={{ id: feed.id }} className="hover:underline">
                        {feed.name}
                      </Link>
                      {feed.description && (
                        <span className="text-xs text-muted-foreground block">{feed.description}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{feed.datasetName}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div className={`h-2 w-2 rounded-full ${SYNC_STATUS_COLORS[feed.syncStatus] || 'bg-gray-400'}`} />
                        <span className="text-sm">{feed.syncStatus}</span>
                      </div>
                      {feed.errorMessage && (
                        <span className="text-xs text-destructive">{feed.errorMessage}</span>
                      )}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {feed.lastSyncAt
                        ? new Date(feed.lastSyncAt).toLocaleString()
                        : '—'}
                    </TableCell>
                    <TableCell className="text-sm">
                      {feed.chunkCount != null ? feed.chunkCount : '—'}
                    </TableCell>
                    <TableCell>
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
                        {feed.status === 'ACTIVE' && (
                          <Button size="sm" variant="outline" onClick={() => disableMutation.mutate(feed.id)}>
                            Disable
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground text-center py-8">No data feeds yet.</p>
          )}
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
    queryFn: knowledgeProviderApi.list,
  })

  const publishedProviders = providers?.filter((p) => p.status === 'ACTIVE') || []

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!publishedProviders.length) return
    onCreate({
      scope: 'ORGANIZATION',
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