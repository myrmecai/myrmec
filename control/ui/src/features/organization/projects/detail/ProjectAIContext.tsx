// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  instructionAssetApi,
  instructionBindingApi,
  knowledgeProviderApi,
} from '@/lib/api'
import type { InstructionAsset } from '@/lib/api'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { BookOpen, Database, FileSearch, Eye } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ProjectInstructionAssets } from './ProjectInstructionAssets'
import { ProjectKnowledgeProviders } from './ProjectKnowledgeProviders'
import { Scope, EntityStatus, Availability } from '@/lib/domain-constants'

export function ProjectAIContext({ projectId }: { projectId: string }) {
  const [activeSubTab, setActiveSubTab] = useState<'assets' | 'providers' | 'sources'>('assets')
  const queryClient = useQueryClient()

  // Fetch org-level items for the "inherited" display.
  // The org-scoped list endpoint now includes `availability` on each asset,
  // so we no longer need a separate published-version fetch per asset.
  const { data: orgAssets } = useQuery({
    queryKey: ['instruction-assets', 'org'],
    queryFn: () => instructionAssetApi.list(),
  })
  const { data: orgProviders } = useQuery({
    queryKey: ['knowledge-providers', 'org'],
    queryFn: () => knowledgeProviderApi.list(),
  })

  // Fetch existing bindings for this project
  const { data: bindings } = useQuery({
    queryKey: ['instruction-bindings', projectId],
    queryFn: () => instructionBindingApi.list(projectId),
  })

  const inheritedAssets = orgAssets?.filter((a) => a.scope === Scope.ORGANIZATION && a.status === EntityStatus.ACTIVE) ?? []
  const inheritedProviders = orgProviders?.filter((p) => p.scope === Scope.ORGANIZATION && p.status === EntityStatus.ACTIVE) ?? []

  // Build a map of assetId → enabled (from bindings)
  const bindingMap = new Map<string, boolean>()
  bindings?.forEach((b) => {
    bindingMap.set(b.instructionAssetId, b.enabled)
  })

  // Mutation to toggle a binding
  const toggleMutation = useMutation({
    mutationFn: ({ assetId, enabled }: { assetId: string; enabled: boolean }) =>
      instructionBindingApi.update(projectId, assetId, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-bindings', projectId] })
    },
  })

  function isAssetEnabled(asset: InstructionAsset): boolean {
    const availability = asset.availability
    if (availability === Availability.REQUIRED) return true
    const binding = bindingMap.get(asset.id)
    // OPTIONAL assets: enabled if binding exists and is true, else false
    return binding ?? false
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-base">AI Context</CardTitle>
              <CardDescription>Project-scoped and inherited organization AI context resources</CardDescription>
            </div>
            <Button asChild variant="outline" size="sm">
              <Link to="/projects/$projectId/ai-context-preview" params={{ projectId }}>
                <Eye className="h-4 w-4 mr-2" />
                Preview Context
              </Link>
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {/* Sub-tab headers */}
          <div className="flex gap-4 border-b mb-4">
            <button
              className={`pb-2 text-sm font-medium ${activeSubTab === 'assets' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
              onClick={() => setActiveSubTab('assets')}
            >Instruction Assets</button>
            <button
              className={`pb-2 text-sm font-medium ${activeSubTab === 'providers' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
              onClick={() => setActiveSubTab('providers')}
            >Knowledge Providers</button>
            <button
              className={`pb-2 text-sm font-medium ${activeSubTab === 'sources' ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}
              onClick={() => setActiveSubTab('sources')}
            >Knowledge Sources</button>
          </div>

          {/* Instruction Assets sub-tab */}
          {activeSubTab === 'assets' && (
            <div className="space-y-4">
              <ProjectInstructionAssets projectId={projectId} />
              {inheritedAssets.length > 0 && (
                <Card>
                  <CardHeader>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <BookOpen className="h-5 w-5 text-muted-foreground" />
                        <CardTitle className="text-base">Inherited Organization Assets</CardTitle>
                      </div>
                      <Badge variant="outline" className="text-xs">{inheritedAssets.length} inherited</Badge>
                    </div>
                    <CardDescription>Organization-level instruction assets inherited by this project</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      {inheritedAssets.map((a) => {
                        const availability = a.availability
                        const enabled = isAssetEnabled(a)
                        const isOptional = availability === Availability.OPTIONAL
                        return (
                          <div
                            key={a.id}
                            data-testid={`inherited-instruction-asset-${a.id}`}
                            className="flex items-center justify-between text-sm"
                          >
                            <div className="flex items-center gap-2">
                              <span className="font-medium">{a.name}</span>
                              <Badge
                                data-testid={`availability-badge-${a.id}`}
                                variant={availability === Availability.REQUIRED ? 'default' : 'secondary'}
                                className="text-xs"
                              >
                                {availability ?? '—'}
                              </Badge>
                            </div>
                            <input
                              type="checkbox"
                              data-testid={`enabled-switch-${a.id}`}
                              checked={enabled}
                              disabled={!isOptional || toggleMutation.isPending}
                              onChange={(e) =>
                                toggleMutation.mutate({ assetId: a.id, enabled: e.target.checked })
                              }
                              className="h-4 w-4"
                            />
                          </div>
                        )
                      })}
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          )}

          {/* Knowledge Providers sub-tab */}
          {activeSubTab === 'providers' && (
            <div className="space-y-4">
              <ProjectKnowledgeProviders projectId={projectId} />
              {inheritedProviders.length > 0 && (
                <Card>
                  <CardHeader>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Database className="h-5 w-5 text-muted-foreground" />
                        <CardTitle className="text-base">Inherited Organization Providers</CardTitle>
                      </div>
                      <Badge variant="outline" className="text-xs">{inheritedProviders.length} inherited</Badge>
                    </div>
                    <CardDescription>Organization-level knowledge providers inherited by this project</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-2">
                      {inheritedProviders.map((p) => (
                        <div key={p.id} className="flex items-center justify-between text-sm">
                          <span className="font-medium">{p.name}</span>
                          <Badge variant="outline">{p.type}</Badge>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          )}

          {/* Knowledge Sources sub-tab */}
          {activeSubTab === 'sources' && (
            <Card>
              <CardHeader>
                <div className="flex items-center gap-2">
                  <FileSearch className="h-5 w-5 text-muted-foreground" />
                  <CardTitle className="text-base">Knowledge Sources</CardTitle>
                </div>
                <CardDescription>Knowledge sources are managed from each provider's detail page</CardDescription>
              </CardHeader>
              <CardContent>
                <p className="text-muted-foreground text-center py-4">
                  Knowledge sources are linked to providers. Navigate to a provider to manage its sources.
                </p>
              </CardContent>
            </Card>
          )}
        </CardContent>
      </Card>
    </div>
  )
}