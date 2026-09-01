// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ArrowLeft, FileText, CheckCircle2, AlertTriangle } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { api } from '@/lib/api'

interface ContextPreviewInstruction {
  assetId: string
  versionId: string
  name: string
  scope: string
  category: string
  sourceType: string
  contentPreview: string | null
  gitCommit: string | null
  inlineVersion: number | null
  priority: number
  tokenCount: number
}

interface ContextPreviewResponse {
  governanceProfileCode: string
  contextPinning: string
  totalTokens: number
  budgetTokens: number
  truncated: boolean
  instructions: ContextPreviewInstruction[]
  knowledgeSources: Array<{
    sourceId: string
    sourceName: string
    providerType: string
    datasetName: string | null
    availability: string
  }>
}

export function EffectiveContextPreviewPage({ projectId }: { projectId: string }) {
  const { data: preview, isLoading, error } = useQuery({
    queryKey: ['context-preview', projectId],
    queryFn: () =>
      api.get(
        `/admin/projects/${projectId}/context-preview?serviceType=CONVERSATION`,
      ) as unknown as ContextPreviewResponse,
  })

  if (isLoading) {
    return <div className="p-8 text-muted-foreground">Loading context preview…</div>
  }

  if (error || !preview) {
    return (
      <div className="p-8">
        <div className="text-destructive">
          Failed to load context preview: {error ? String(error) : 'No data'}
        </div>
      </div>
    )
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Link
          to="/projects/$projectId/ai-context"
          params={{ projectId }}
          className="hover:underline flex items-center gap-1"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to AI Context
        </Link>
      </div>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Effective Context Preview</h1>
          <p className="text-muted-foreground">
            The resolved instruction stack for this project (CONVERSATION service type).
          </p>
        </div>
        <Badge variant={preview.truncated ? 'destructive' : 'secondary'}>
          {preview.truncated ? 'Truncated' : 'Within budget'}
        </Badge>
      </div>

      <Card data-testid="token-budget-card">
        <CardHeader>
          <CardTitle className="text-base">Token Budget</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            <div className="flex items-center justify-between text-sm">
              <span data-testid="token-budget-used">{preview.totalTokens}</span>
              <span className="text-muted-foreground">/ {preview.budgetTokens}</span>
            </div>
            <div className="w-full h-2 bg-muted rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full ${preview.truncated ? 'bg-destructive' : 'bg-primary'}`}
                style={{
                  width: `${Math.min(100, (preview.totalTokens / preview.budgetTokens) * 100)}%`,
                }}
              />
            </div>
            {preview.truncated && (
              <div className="flex items-center gap-2 text-sm text-destructive" data-testid="truncation-warning">
                <AlertTriangle className="h-4 w-4" />
                Context truncated — some instructions were dropped to fit the token budget.
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <CheckCircle2 className="h-5 w-5 text-green-600" />
            <CardTitle className="text-base">
              Included Instructions ({preview.instructions.length})
            </CardTitle>
          </div>
          <CardDescription>
            Ordered by priority (lowest first, highest closest to user message)
          </CardDescription>
        </CardHeader>
        <CardContent>
          {preview.instructions.length === 0 ? (
            <p className="text-muted-foreground text-center py-4">No instructions included.</p>
          ) : (
            <div className="space-y-3">
              {preview.instructions.map((instr, idx) => (
                <div
                  key={instr.assetId}
                  data-testid={`context-instruction-${idx}`}
                  className="flex items-start justify-between gap-4 p-3 rounded-lg border"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{instr.name}</span>
                      <Badge variant="outline" className="text-xs">{instr.scope}</Badge>
                      <Badge variant="secondary" className="text-xs">{instr.category}</Badge>
                      <Badge variant="outline" className="text-xs">{instr.sourceType}</Badge>
                    </div>
                    {instr.contentPreview && (
                      <p className="text-sm text-muted-foreground line-clamp-2">
                        {instr.contentPreview}
                      </p>
                    )}
                  </div>
                  <div className="flex flex-col items-end gap-1 text-sm text-muted-foreground whitespace-nowrap">
                    <span data-testid={`instruction-priority-${idx}`}>priority: {instr.priority}</span>
                    <span data-testid={`instruction-tokens-${idx}`}>{instr.tokenCount} tokens</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {preview.knowledgeSources.length > 0 && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <FileText className="h-5 w-5 text-muted-foreground" />
              <CardTitle className="text-base">
                Knowledge Sources ({preview.knowledgeSources.length})
              </CardTitle>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {preview.knowledgeSources.map((ks) => (
                <div key={ks.sourceId} className="flex items-center justify-between text-sm">
                  <span className="font-medium">{ks.sourceName}</span>
                  <div className="flex items-center gap-2">
                    <Badge variant="outline" className="text-xs">{ks.providerType}</Badge>
                    <Badge variant="secondary" className="text-xs">{ks.availability}</Badge>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Governance Metadata</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <span className="text-muted-foreground">Profile:</span>{' '}
              <span className="font-medium">{preview.governanceProfileCode}</span>
            </div>
            <div>
              <span className="text-muted-foreground">Context Pinning:</span>{' '}
              <span className="font-medium">{preview.contextPinning}</span>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}