// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { knowledgeSourceApi } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { FileSearch, PowerOff, Archive } from 'lucide-react'
import { STATUS_COLORS } from '../shared/constants'

export function KnowledgeSourceDetail({ sourceId }: { sourceId: string }) {
  const queryClient = useQueryClient()

  const { data: source, isLoading } = useQuery({
    queryKey: ['knowledge-source', sourceId],
    queryFn: () => knowledgeSourceApi.get(sourceId),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['knowledge-source', sourceId] })
    queryClient.invalidateQueries({ queryKey: ['knowledge-sources'] })
  }

  const disableMutation = useMutation({
    mutationFn: () => knowledgeSourceApi.disable(sourceId),
    onSuccess: invalidate,
  })

  const archiveMutation = useMutation({
    mutationFn: () => knowledgeSourceApi.archive(sourceId),
    onSuccess: invalidate,
  })

  if (isLoading || !source) {
    return <div className="p-8 text-muted-foreground">Loading knowledge source…</div>
  }

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
        <Link to="/platform/ai-context/knowledge-sources" className="hover:underline">Knowledge Sources</Link>
        <span>/</span>
        <span className="text-foreground font-medium">{source.name}</span>
      </div>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <FileSearch className="h-6 w-6 text-muted-foreground" />
          <div>
            <h1 className="text-2xl font-bold">{source.name}</h1>
            {source.description && <p className="text-muted-foreground">{source.description}</p>}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{source.availability}</Badge>
          <div className="flex items-center gap-1">
            <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[source.status] || 'bg-gray-400'}`} />
            <span className="text-sm">{source.status}</span>
          </div>
        </div>
      </div>

      <Card className="mb-6">
        <CardHeader><CardTitle className="text-base">Identity & Configuration</CardTitle></CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div><dt className="text-muted-foreground">Provider Version</dt><dd>{source.providerVersionId}</dd></div>
            <div><dt className="text-muted-foreground">Availability</dt><dd>{source.availability}</dd></div>
            <div><dt className="text-muted-foreground">Priority</dt><dd>{source.priority}</dd></div>
            <div><dt className="text-muted-foreground">Scope</dt><dd>{source.scope}</dd></div>
            <div><dt className="text-muted-foreground">Created</dt><dd>{new Date(source.createdAt).toLocaleString()}</dd></div>
            <div><dt className="text-muted-foreground">Updated</dt><dd>{new Date(source.updatedAt).toLocaleString()}</dd></div>
          </dl>
          {source.config && (
            <div className="mt-4">
              <span className="text-sm text-muted-foreground mb-2 block">Config</span>
              <pre className="text-xs bg-muted/50 rounded-md p-3 whitespace-pre-wrap max-h-48 overflow-y-auto">
                {JSON.stringify(source.config, null, 2)}
              </pre>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="flex items-center gap-2 mt-6">
        {source.status === 'ACTIVE' && (
          <Button variant="outline" onClick={() => disableMutation.mutate()} disabled={disableMutation.isPending}>
            <PowerOff className="h-4 w-4 mr-2" /> Disable
          </Button>
        )}
        {source.status !== 'ARCHIVED' && (
          <Button variant="ghost" className="text-destructive" onClick={() => { if (confirm(`Archive "${source.name}"?`)) archiveMutation.mutate() }} disabled={archiveMutation.isPending}>
            <Archive className="h-4 w-4 mr-2" /> Archive
          </Button>
        )}
      </div>
    </ContentAreaLayout>
  )
}