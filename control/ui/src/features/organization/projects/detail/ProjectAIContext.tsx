// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  instructionAssetApi,
  knowledgeProviderApi,
} from '@/lib/api'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { BookOpen, Database, FileSearch } from 'lucide-react'
import { ProjectInstructionAssets } from './ProjectInstructionAssets'
import { ProjectKnowledgeProviders } from './ProjectKnowledgeProviders'

export function ProjectAIContext({ projectId }: { projectId: string }) {
  const [activeSubTab, setActiveSubTab] = useState<'assets' | 'providers' | 'sources'>('assets')

  // Fetch org-level items for the "inherited" display
  const { data: orgAssets } = useQuery({
    queryKey: ['instruction-assets', 'org'],
    queryFn: () => instructionAssetApi.list(),
  })
  const { data: orgProviders } = useQuery({
    queryKey: ['knowledge-providers', 'org'],
    queryFn: () => knowledgeProviderApi.list(),
  })

  const inheritedAssets = orgAssets?.filter((a) => a.scope === 'ORGANIZATION' && a.status === 'ACTIVE') ?? []
  const inheritedProviders = orgProviders?.filter((p) => p.scope === 'ORGANIZATION' && p.status === 'ACTIVE') ?? []

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">AI Context</CardTitle>
          <CardDescription>Project-scoped and inherited organization AI context resources</CardDescription>
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
                      {inheritedAssets.map((a) => (
                        <div key={a.id} className="flex items-center justify-between text-sm">
                          <span className="font-medium">{a.name}</span>
                          <Badge variant="outline">{a.status}</Badge>
                        </div>
                      ))}
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