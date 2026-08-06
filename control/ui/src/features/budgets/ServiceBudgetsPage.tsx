// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { budgetsApi, projectsApi, type QuotaResourceType, type QuotaPeriod, type EffectiveQuota, type QuotaServiceType } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { formatAmount, formatResourceType, formatPeriod, calculatePercentage, statusFromPercentage } from './lib/format'
import { QuotaProgressBar } from './components/quota-progress-bar'

interface ServiceBudgetsPageProps {
  projectId: string
}

export function ServiceBudgetsPage({ projectId }: ServiceBudgetsPageProps) {
  const auth = useAuth()
  const [resourceType, setResourceType] = useState<QuotaResourceType>('COST_USD_CENTS')
  const [period, setPeriod] = useState<QuotaPeriod>('MONTHLY_CALENDAR')

  const { data: project, isLoading: projectLoading } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['project-service-budgets', projectId, resourceType, period],
    queryFn: () => budgetsApi.projectServices(projectId, resourceType, period),
  })

  const canCreate = auth.hasProjectRole(projectId, 'PROJECT_OWNER') ||
    auth.hasProjectRole(projectId, 'EDITOR') ||
    auth.hasProjectRole(projectId, 'BUDGET_OWNER') ||
    (project?.groupId ? auth.hasGroupRole(project.groupId, 'BUDGET_OWNER') : false) ||
    auth.isPlatformAdmin ||
    auth.hasSystemRole('BUDGET_OWNER')

  const pool = data?.sharedPool

  return (
    <ContentAreaLayout maxWidth="56rem">
      <div className="space-y-6">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Link to="/budgets/projects/$projectId" params={{ projectId }} className="hover:underline flex items-center gap-1">
            <ArrowLeft className="h-4 w-4" />
            Back to project budget
          </Link>
        </div>

        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{project ? project.name : 'Project'} — Service budgets</h1>
            <p className="text-muted-foreground">
              {formatPeriod(period)} {formatResourceType(resourceType).toLowerCase()} allocation across services.
            </p>
          </div>
          {canCreate && (
            <Button asChild>
              <Link to="/budgets/new" search={{ scopeType: 'SERVICE', scopeId: projectId }}>
                <Plus className="h-4 w-4 mr-2" />
                Allocate budget
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

        {pool && pool.totalLimit > 0 && (
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-base">Project budget pool</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex items-center justify-between text-sm">
                <span>Total {formatAmount(pool.totalLimit, resourceType)}</span>
                <span>Reserved {formatAmount(pool.reservedAmount, resourceType)}</span>
                <span>Shared {formatAmount(pool.sharedAmount, resourceType)}</span>
              </div>
              <PoolBar
                total={pool.totalLimit}
                reserved={pool.reservedAmount}
                shared={pool.sharedAmount}
                consumedInShared={pool.consumedInShared}
                resourceType={resourceType}
              />
            </CardContent>
          </Card>
        )}

        {isLoading || projectLoading ? (
          <div className="h-64 flex items-center justify-center text-muted-foreground">Loading service budgets…</div>
        ) : error ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-destructive text-base">Failed to load service budgets</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{error instanceof Error ? error.message : 'Unknown error'}</p>
              <Button variant="outline" onClick={() => refetch()}>Retry</Button>
            </CardContent>
          </Card>
        ) : (
          <div className="border rounded-lg overflow-hidden">
            <div className="grid grid-cols-12 gap-4 px-4 py-2 bg-muted font-medium text-sm border-b">
              <div className="col-span-4">Service</div>
              <div className="col-span-2">Type</div>
              <div className="col-span-2">Limit</div>
              <div className="col-span-3">Used</div>
              <div className="col-span-1 text-right">Action</div>
            </div>
            {(data?.serviceBudgets ?? []).length === 0 && (
              <div className="px-4 py-8 text-center text-muted-foreground">
                No services found for this project.
              </div>
            )}
            {(data?.serviceBudgets ?? []).map((s) => (
              <ServiceRow key={s.id} service={s} projectId={projectId} canCreate={canCreate} />
            ))}
          </div>
        )}
      </div>
    </ContentAreaLayout>
  )
}

function ServiceRow({
  service,
  projectId,
  canCreate,
}: {
  service: EffectiveQuota
  projectId: string
  canCreate: boolean
}) {
  const percentage = calculatePercentage(service.consumed, service.effectiveLimit)
  const status = statusFromPercentage(percentage, service.paused)
  const hasBudget = service.quotaType !== null
  const typeLabel = service.quotaType === 'RESERVATION' ? 'Res.' : service.quotaType === 'CEILING' ? 'Ceil.' : '—'

  return (
    <div className="grid grid-cols-12 gap-4 items-center px-4 py-3 border-b last:border-b-0">
      <div className="col-span-4">
        <div className="font-medium">{service.name}</div>
        <div className="text-xs text-muted-foreground">{serviceTypeLabel(service.serviceType ?? null)}</div>
      </div>
      <div className="col-span-2 text-sm">{typeLabel}</div>
      <div className="col-span-2 text-sm">
        {service.effectiveLimit !== null ? formatAmount(service.effectiveLimit, service.resourceType) : '—'}
      </div>
      <div className="col-span-3">
        {hasBudget ? (
          <QuotaProgressBar consumed={service.consumed} limit={service.effectiveLimit} paused={service.paused} size="sm" />
        ) : (
          <span className="text-sm text-muted-foreground">No budget set — shares project pool</span>
        )}
      </div>
      <div className="col-span-1 text-right">
        {canCreate && (
          <Button variant="outline" size="sm" asChild>
            <Link
              to="/budgets/new"
              search={{
                scopeType: 'SERVICE',
                scopeId: projectId,
                serviceType: service.serviceType ?? 'WORKFLOW',
              }}
            >
              {hasBudget ? 'Edit' : 'Set'}
            </Link>
          </Button>
        )}
      </div>
      {status !== 'ok' && status !== 'paused' && (
        <div className="col-span-12 -mt-2 mb-1">
          <Badge
            variant="secondary"
            className={
              status === 'exceeded'
                ? 'bg-destructive/10 text-destructive'
                : 'bg-amber-100 text-amber-800'
            }
          >
            {status === 'exceeded' ? 'Over budget' : 'At risk'}
          </Badge>
        </div>
      )}
    </div>
  )
}

function serviceTypeLabel(type: QuotaServiceType | null): string {
  switch (type) {
    case 'WORKFLOW': return 'Workflow'
    case 'CONVERSATIONAL': return 'Assistant'
    default: return 'Service'
  }
}

function PoolBar({
  total,
  reserved,
  shared,
  consumedInShared,
  resourceType,
}: {
  total: number
  reserved: number
  shared: number
  consumedInShared: number
  resourceType: QuotaResourceType
}) {
  const reservedPct = total > 0 ? (reserved / total) * 100 : 0
  const sharedPct = total > 0 ? (shared / total) * 100 : 0
  const consumedPct = shared > 0 ? Math.min(100, (consumedInShared / shared) * 100) : 0

  return (
    <div className="space-y-1">
      <div className="h-3 w-full rounded-full bg-muted overflow-hidden flex">
        <div className="h-full bg-slate-400" style={{ width: `${reservedPct}%` }} />
        <div className="h-full bg-emerald-500" style={{ width: `${sharedPct}%` }} />
      </div>
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>Reserved {formatAmount(reserved, resourceType)} ({Math.round(reservedPct)}%)</span>
        <span>Shared consumed {formatAmount(consumedInShared, resourceType)} ({Math.round(consumedPct)}%)</span>
      </div>
    </div>
  )
}

function LabelSmall({ children }: { children: React.ReactNode }) {
  return <span className="text-xs font-medium text-muted-foreground">{children}</span>
}
