// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import {
  projectsApi,
  instructionAssetApi,
  knowledgeProviderApi,
  knowledgeSourceApi,
  dataFeedApi,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ArrowLeft, BookOpen, Database, FileSearch, Rss, Gauge } from 'lucide-react'

export function ProjectAIContext({ projectId }: { projectId: string }) {
  const navigate = useNavigate()

  const { data: project } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const { data: instructionAssets } = useQuery({
    queryKey: ['instruction-assets'],
    queryFn: instructionAssetApi.list,
  })

  const { data: providers } = useQuery({
    queryKey: ['knowledge-providers'],
    queryFn: knowledgeProviderApi.list,
  })

  const { data: sources } = useQuery({
    queryKey: ['knowledge-sources'],
    queryFn: knowledgeSourceApi.list,
  })

  const { data: feeds } = useQuery({
    queryKey: ['data-feeds'],
    queryFn: dataFeedApi.list,
  })

  // Filter to project-scoped items
  const projectAssets = instructionAssets?.filter((a) => a.scope === 'PROJECT' && a.projectId === projectId) ?? []
  const projectProviders = providers?.filter((p) => p.scope === 'PROJECT' && p.projectId === projectId) ?? []
  const projectSources = sources?.filter((s) => s.scope === 'PROJECT' && s.projectId === projectId) ?? []
  const projectFeeds = feeds?.filter((f) => f.scope === 'PROJECT' && f.projectId === projectId) ?? []

  // Inherited org-level items
  const orgAssets = instructionAssets?.filter((a) => a.scope === 'ORGANIZATION' && a.status === 'ACTIVE') ?? []
  const orgProviders = providers?.filter((p) => p.scope === 'ORGANIZATION' && p.status === 'ACTIVE') ?? []

  const tabs = [
    { label: 'Instruction Assets', icon: BookOpen, count: projectAssets.length, inherited: orgAssets.length, to: '/platform/ai-context/instruction-assets' },
    { label: 'Knowledge Providers', icon: Database, count: projectProviders.length, inherited: orgProviders.length, to: '/platform/ai-context/knowledge-providers' },
    { label: 'Knowledge Sources', icon: FileSearch, count: projectSources.length, inherited: 0, to: '/platform/ai-context/knowledge-sources' },
    { label: 'Data Feeds', icon: Rss, count: projectFeeds.length, inherited: 0, to: '/platform/ai-context/data-feeds' },
  ]

  return (
    <div className="p-8 max-w-5xl">
      <Button
        variant="ghost"
        size="sm"
        className="mb-4"
        onClick={() => navigate({ to: '/projects' })}
      >
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back to projects
      </Button>

      <div className="mb-6">
        <h1 className="text-3xl font-bold">AI Context</h1>
        <p className="text-muted-foreground">
          {project?.name ?? 'Project'} — manage instruction assets, knowledge providers, and data feeds
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
        {tabs.map((tab) => {
          const Icon = tab.icon
          return (
            <Card key={tab.label}>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Icon className="h-5 w-5 text-muted-foreground" />
                    <CardTitle className="text-base">{tab.label}</CardTitle>
                  </div>
                  <div className="flex items-center gap-2">
                    {tab.inherited > 0 && (
                      <Badge variant="outline" className="text-xs">
                        {tab.inherited} inherited
                      </Badge>
                    )}
                    <Badge variant="secondary" className="text-xs">
                      {tab.count} project
                    </Badge>
                  </div>
                </div>
                <CardDescription>
                  Manage project-level {tab.label.toLowerCase()} and view inherited org-level assets
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Link to={tab.to}>
                  <Button size="sm" variant="outline">
                    Manage →
                  </Button>
                </Link>
              </CardContent>
            </Card>
          )
        })}
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <Gauge className="h-5 w-5 text-muted-foreground" />
            <CardTitle className="text-base">Governance Profile</CardTitle>
          </div>
          <CardDescription>
            View the governance profile that controls context assembly policies for this project
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Link to="/platform/ai-context/governance-profile">
            <Button size="sm" variant="outline">
              View Governance Profiles →
            </Button>
          </Link>
        </CardContent>
      </Card>
    </div>
  )
}