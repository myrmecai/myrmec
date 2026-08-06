// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { budgetsApi, type QuotaResourceType, type QuotaPeriod } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { BudgetSummaryCards } from './components/budget-summary-cards'
import { BudgetFilters } from './components/budget-filters'
import { BudgetAlerts } from './components/budget-alerts'
import { BudgetTree } from './components/budget-tree'

export function BudgetsDashboardPage() {
  const { isPlatformAdmin, hasSystemRole } = useAuth()
  const canCreate = isPlatformAdmin || hasSystemRole('BUDGET_OWNER')

  const [period, setPeriod] = useState<QuotaPeriod>('MONTHLY_CALENDAR')
  const [resourceType, setResourceType] = useState<QuotaResourceType>('COST_USD_CENTS')
  const [show, setShow] = useState<'all' | 'at-risk' | 'exceeded' | 'paused'>('all')

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['budgets-dashboard', resourceType, period],
    queryFn: () => budgetsApi.dashboard(resourceType, period),
  })

  return (
    <ContentAreaLayout>
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight">Budgets</h1>
          {canCreate && (
            <Button asChild>
              <Link to="/budgets/new">
                <Plus className="h-4 w-4 mr-2" />
                New Budget
              </Link>
            </Button>
          )}
        </div>

        <BudgetFilters
          period={period}
          resourceType={resourceType}
          show={show}
          onPeriodChange={setPeriod}
          onResourceTypeChange={setResourceType}
          onShowChange={setShow}
        />

        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <p className="text-muted-foreground">Loading budgets...</p>
          </div>
        ) : error ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-destructive">Failed to load budgets</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">
                {error instanceof Error ? error.message : 'Unknown error'}
              </p>
              <Button variant="outline" onClick={() => refetch()}>Retry</Button>
            </CardContent>
          </Card>
        ) : (
          <>
            <BudgetSummaryCards tree={data?.tree ?? []} resourceType={resourceType} />

            <Card>
              <CardHeader>
                <CardTitle>Alerts</CardTitle>
              </CardHeader>
              <CardContent>
                <BudgetAlerts nodes={data?.tree ?? []} resourceType={resourceType} />
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Scope tree</CardTitle>
              </CardHeader>
              <CardContent>
                <BudgetTree
                  nodes={data?.tree ?? []}
                  resourceType={resourceType}
                  showFilter={show}
                />
              </CardContent>
            </Card>
          </>
        )}
      </div>
    </ContentAreaLayout>
  )
}
