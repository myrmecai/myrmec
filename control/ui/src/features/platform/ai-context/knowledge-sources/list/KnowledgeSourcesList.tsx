// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  knowledgeSourceApi,
  knowledgeProviderApi,
  type KnowledgeProvider,
} from '@/lib/api'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { AlertCircle, ExternalLink } from 'lucide-react'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

export function KnowledgeSourcesList() {
  const { data: sources, isLoading, error } = useQuery({
    queryKey: ['knowledge-sources'],
    queryFn: knowledgeSourceApi.list,
  })

  const { data: providers } = useQuery({
    queryKey: ['knowledge-providers'],
    queryFn: () => knowledgeProviderApi.list(),
  })

  // Fetch all provider versions (published + draft) to build version→provider lookup
  const providerVersionQueries = useQuery({
    queryKey: ['all-provider-versions', providers?.map(p => p.id).join(',')],
    queryFn: async () => {
      if (!providers) return new Map<string, KnowledgeProvider>()
      const entries = await Promise.all(
        providers.map(async (p) => {
          const versionIds: string[] = []
          // Fetch published version
          try {
            const pub = await knowledgeProviderApi.getPublishedVersion(p.id)
            if (pub) versionIds.push(pub.id)
          } catch { /* no published version */ }
          // Fetch draft version
          try {
            const draft = await knowledgeProviderApi.getDraftVersion(p.id)
            if (draft) versionIds.push(draft.id)
          } catch { /* no draft */ }
          // Fetch all versions (for archived)
          try {
            const all = await knowledgeProviderApi.getVersions(p.id)
            for (const v of all) versionIds.push(v.id)
          } catch { /* no versions */ }
          return versionIds.map(vid => [vid, p] as const)
        })
      )
      const map = new Map<string, KnowledgeProvider>()
      for (const providerEntries of entries) {
        for (const [vid, p] of providerEntries) {
          map.set(vid, p)
        }
      }
      return map
    },
    enabled: !!providers,
  })

  const versionMap = providerVersionQueries.data ?? new Map<string, KnowledgeProvider>()

  const columns: ColumnDef<NonNullable<typeof sources>[number]>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => (
        <div className="font-medium">
          <Link to="/platform/ai-context/knowledge-sources/$id" params={{ id: row.original.id }} className="hover:underline">
            {row.original.name}
          </Link>
          {row.original.description && (
            <span className="text-xs text-muted-foreground block">{row.original.description}</span>
          )}
        </div>
      ),
    },
    {
      id: 'provider',
      header: 'Provider',
      enableSorting: false,
      cell: ({ row }) => {
        const provider = versionMap.get(row.original.providerVersionId)
        if (!provider) return <span className="text-sm text-muted-foreground">—</span>
        return (
          <Link
            to="/platform/ai-context/knowledge-providers/$id"
            params={{ id: provider.id }}
            className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
          >
            {provider.name}
            <ExternalLink className="h-3 w-3" />
          </Link>
        )
      },
    },
    {
      accessorKey: 'createdAt',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Created" />,
      cell: ({ row }) => (
        <span className="text-sm text-muted-foreground">
          {new Date(row.original.createdAt).toLocaleDateString()}
        </span>
      ),
    },
  ], [versionMap])

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load knowledge sources</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Knowledge Sources</h1>
        <p className="text-muted-foreground">All knowledge sources across providers. Manage sources from each provider's detail page.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Sources</CardTitle>
          <CardDescription>Read-only overview — navigate to a provider to add, edit, or delete sources</CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={sources ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>
    </div>
    </ContentAreaLayout>
  )
}