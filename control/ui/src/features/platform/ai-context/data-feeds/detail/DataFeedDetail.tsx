// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { dataFeedApi } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Rss, RefreshCw, PowerOff } from 'lucide-react'
import { SYNC_STATUS_COLORS } from '../shared/constants'

export function DataFeedDetail({ feedId }: { feedId: string }) {
  const queryClient = useQueryClient()

  const { data: feed, isLoading } = useQuery({
    queryKey: ['data-feed', feedId],
    queryFn: () => dataFeedApi.get(feedId),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['data-feed', feedId] })
    queryClient.invalidateQueries({ queryKey: ['data-feeds'] })
  }

  const syncMutation = useMutation({
    mutationFn: () => dataFeedApi.triggerSync(feedId),
    onSuccess: invalidate,
  })

  const disableMutation = useMutation({
    mutationFn: () => dataFeedApi.disable(feedId),
    onSuccess: invalidate,
  })

  if (isLoading || !feed) {
    return <div className="p-8 text-muted-foreground">Loading data feed…</div>
  }

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/data-feeds" className="hover:underline">Data Feeds</Link>
        <span>/</span>
        <span className="text-foreground font-medium">{feed.name}</span>
      </div>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <Rss className="h-6 w-6 text-muted-foreground" />
          <div>
            <h1 className="text-2xl font-bold">{feed.name}</h1>
            {feed.description && <p className="text-muted-foreground">{feed.description}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{feed.datasetName}</Badge>
          <div className="flex items-center gap-1">
            <div className={`h-2 w-2 rounded-full ${SYNC_STATUS_COLORS[feed.syncStatus] || 'bg-gray-400'}`} />
            <span className="text-sm">{feed.syncStatus}</span>
          </div>
        </div>
      </div>

      <Card className="mb-6">
        <CardHeader><CardTitle className="text-base">Identity & Configuration</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Provider Version</dt><dd>{feed.providerVersionId}</dd></div>
            <div><dt className="text-muted-foreground">Dataset Name</dt><dd>{feed.datasetName}</dd></div>
            <div><dt className="text-muted-foreground">Sync Schedule</dt><dd>{feed.syncSchedule || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Connection Config</dt><dd>{feed.connectionConfigId || '—'}</dd></div>
            <div><dt className="text-muted-foreground">Last Sync</dt><dd>{feed.lastSyncAt ? new Date(feed.lastSyncAt).toLocaleString() : '—'}</dd></div>
            <div><dt className="text-muted-foreground">Chunk Count</dt><dd>{feed.chunkCount ?? '—'}</dd></div>
            <div><dt className="text-muted-foreground">Status</dt><dd>{feed.status}</dd></div>
            <div><dt className="text-muted-foreground">Scope</dt><dd>{feed.scope}</dd></div>
          </dl>
          {feed.connectionDetails && (
            <div className="mt-4">
              <span className="text-sm text-muted-foreground mb-2 block">Connection Details</span>
              <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                {JSON.stringify(feed.connectionDetails, null, 2)}
              </pre>
            </div>
          )}
          {feed.errorMessage && (
            <div className="mt-4 text-sm text-destructive">
              <span className="font-medium">Error:</span> {feed.errorMessage}
            </div>
          )}
        </CardContent>
      </Card>

      <div className="flex items-center gap-2 mt-6">
        <Button variant="outline" onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending || feed.status !== 'ACTIVE'}>
          <RefreshCw className="h-4 w-4 mr-2" /> {syncMutation.isPending ? 'Syncing…' : 'Sync Now'}
        </Button>
        {feed.status === 'ACTIVE' && (
          <Button variant="outline" onClick={() => disableMutation.mutate()} disabled={disableMutation.isPending}>
            <PowerOff className="h-4 w-4 mr-2" /> Disable
          </Button>
        )}
      </div>
    </ContentAreaLayout>
  )
}