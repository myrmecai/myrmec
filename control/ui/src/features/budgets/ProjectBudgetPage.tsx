// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { budgetsApi, quotasApi, projectsApi, type QuotaResourceType, type QuotaPeriod, type EffectiveQuota } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { dialogService } from '@/services/dialog-service'
import { formatResourceType, formatPeriod } from './lib/format'
import { EffectiveQuotaCard } from './components/effective-quota-card'
import { canMutateBudget, canCreateBudget } from './lib/budget-permissions'

interface ProjectBudgetPageProps {
  projectId: string
}

export function ProjectBudgetPage({ projectId }: ProjectBudgetPageProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const auth = useAuth()
  const [resourceType, setResourceType] = useState<QuotaResourceType>('COST_USD_CENTS')
  const [period, setPeriod] = useState<QuotaPeriod>('MONTHLY_CALENDAR')
  const [activeTab, setActiveTab] = useState<'budgets' | 'usage' | 'history' | 'services'>('budgets')

  const { data: project, isLoading: projectLoading } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['project-effective-quotas', projectId, resourceType, period],
    queryFn: () => budgetsApi.projectEffectiveQuotas(projectId, resourceType, period),
  })

  const pauseMutation = useMutation({
    mutationFn: (id: string) => quotasApi.pause(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-effective-quotas', projectId] }),
  })

  const resumeMutation = useMutation({
    mutationFn: (id: string) => quotasApi.resume(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-effective-quotas', projectId] }),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => quotasApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-effective-quotas', projectId] }),
  })

  const canMutateHere = canMutateBudget(auth, 'PROJECT', projectId)
  const canCreateHere = canCreateBudget(auth, 'PROJECT', projectId)

  const handleDelete = async (quota: EffectiveQuota) => {
    if (!quota.id) return
    const confirmed = await dialogService.showConfirmDialog({
      title: 'Delete Budget',
      message: `Delete this ${formatPeriod(quota.period).toLowerCase()} ${formatResourceType(quota.resourceType).toLowerCase()} budget? Child scopes will fall back to the next ancestor limit.`,
      severity: 'error',
      type: 'warning',
      confirmLabel: 'Delete',
    })
    if (confirmed) deleteMutation.mutate(quota.id)
  }

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="space-y-6">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Link to="/budgets" className="hover:underline flex items-center gap-1">
            <ArrowLeft className="h-4 w-4" />
            Back to Budgets
          </Link>
        </div>

        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">
              {project ? project.name : 'Project'} budgets
            </h1>
            <p className="text-muted-foreground">
              {project?.description || 'Budgets and effective limits for this project.'}
            </p>
          </div>
          {canCreateHere && (
            <Button asChild>
              <Link to="/budgets/new" search={{ scopeType: 'PROJECT', scopeId: projectId }}>
                <Plus className="h-4 w-4 mr-2" />
                New Budget
              </Link>
            </Button>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-4">
          <div className="grid gap-2">
            <LabelSmall>Resource</LabelSmall>
            <Select value={resourceType} onValueChange={(v) => setResourceType(v as QuotaResourceType)}>
              <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="COST_USD_CENTS">Cost USD</SelectItem>
                <SelectItem value="TOKENS">Tokens</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-2">
            <LabelSmall>Period</LabelSmall>
            <Select value={period} onValueChange={(v) => setPeriod(v as QuotaPeriod)}>
              <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="DAILY">Today</SelectItem>
                <SelectItem value="MONTHLY_CALENDAR">This month</SelectItem>
                <SelectItem value="LIFETIME">Lifetime</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as typeof activeTab)}>
          <TabsList>
            <TabsTrigger value="budgets">Budgets</TabsTrigger>
            <TabsTrigger value="usage">Usage</TabsTrigger>
            <TabsTrigger value="history">History</TabsTrigger>
            <TabsTrigger value="services">Services</TabsTrigger>
          </TabsList>

          <TabsContent value="budgets" className="space-y-4">
            {isLoading || projectLoading ? (
              <div className="h-64 flex items-center justify-center text-muted-foreground">Loading budgets…</div>
            ) : error ? (
              <Card>
                <CardHeader>
                  <CardTitle className="text-destructive text-base">Failed to load budgets</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground mb-4">{error instanceof Error ? error.message : 'Unknown error'}</p>
                  <Button variant="outline" onClick={() => refetch()}>Retry</Button>
                </CardContent>
              </Card>
            ) : (
              <>
                {(data?.quotas ?? []).length === 0 && (
                  <Card>
                    <CardContent className="py-8 text-center text-muted-foreground">
                      No budgets defined for this resource and period.
                      {canCreateHere && (
                        <div className="mt-4">
                          <Button asChild>
                            <Link to="/budgets/new" search={{ scopeType: 'PROJECT', scopeId: projectId }}>
                              <Plus className="h-4 w-4 mr-2" />
                              Add Budget
                            </Link>
                          </Button>
                        </div>
                      )}
                    </CardContent>
                  </Card>
                )}
                {(data?.quotas ?? []).map((q) => (
                  <EffectiveQuotaCard
                    key={q.id}
                    quota={q}
                    resourceType={resourceType}
                    period={period}
                    canMutate={canMutateHere}
                    groupId={project?.groupId}
                    onEdit={(id: string) => navigate({ to: '/budgets/edit/$quotaId', params: { quotaId: id }, search: { scopeType: q.scopeType, scopeId: q.scopeId, groupId: project?.groupId } })}
                    onPause={(id: string) => pauseMutation.mutate(id)}
                    onResume={(id: string) => resumeMutation.mutate(id)}
                    onDelete={(id: string) => handleDelete({ ...q, id })}
                  />
                ))}
              </>
            )}
          </TabsContent>

          <TabsContent value="usage">
            <Card>
              <CardContent className="py-8 text-center text-muted-foreground">
                Usage charts will appear here.
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="history">
            <Card>
              <CardContent className="py-8 text-center text-muted-foreground">
                Budget audit history will appear here.
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="services">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base">Service budgets</CardTitle>
                  <Button variant="outline" size="sm" asChild>
                    <Link to="/budgets/projects/$projectId/services" params={{ projectId }}>
                      View all
                    </Link>
                  </Button>
                </div>
              </CardHeader>
              <CardContent className="py-4 text-center text-muted-foreground">
                Service budget preview and allocation available on the full service budgets page.
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </ContentAreaLayout>
  )
}

function LabelSmall({ children }: { children: React.ReactNode }) {
  return <span className="text-xs font-medium text-muted-foreground">{children}</span>
}
