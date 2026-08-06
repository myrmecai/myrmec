// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState, useMemo } from 'react'
import { useNavigate, useSearch, Link } from '@tanstack/react-router'
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { quotasApi, budgetsApi, type CreateQuotaRequest, type QuotaScope, type QuotaServiceType } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { dialogService } from '@/services/dialog-service'
import { BudgetForm, type BudgetFormValues } from './components/budget-form'
import { canCreateBudget } from './lib/budget-permissions'
import { formatAmount } from './lib/format'

interface NewBudgetPageSearch {
  scopeType?: QuotaScope
  scopeId?: string
  serviceType?: QuotaServiceType
}

export function NewBudgetPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const auth = useAuth()
  const search = useSearch({ from: '/_authenticated/budgets/new' }) as NewBudgetPageSearch

  const [values, setValues] = useState<BudgetFormValues>({
    scopeType: search.scopeType ?? 'ORG',
    scopeId: search.scopeId ?? '',
    resourceType: 'COST_USD_CENTS',
    period: 'MONTHLY_CALENDAR',
    limitAmount: '',
    quotaType: 'CEILING',
    enforcementMode: 'BLOCK',
    maxExecutionAmount: '',
    serviceType: search.serviceType ?? '',
    tagsJson: '',
  })

  const scopeKey = useMemo(() => {
    if (values.scopeType === 'SERVICE') return { type: 'PROJECT' as const, id: values.scopeId }
    if (values.scopeType === 'PROJECT') return { type: 'PROJECT' as const, id: values.scopeId }
    if (values.scopeType === 'GROUP') return { type: 'GROUP' as const, id: values.scopeId }
    return null
  }, [values.scopeType, values.scopeId])

  const { data: parentQuotas } = useQuery({
    queryKey: ['effective-quotas', scopeKey?.type, scopeKey?.id, values.resourceType, values.period],
    queryFn: () => {
      if (!scopeKey) return Promise.resolve({ quotas: [] })
      if (scopeKey.type === 'PROJECT') {
        return budgetsApi.projectEffectiveQuotas(scopeKey.id, values.resourceType, values.period)
      }
      return budgetsApi.groupEffectiveQuotas(scopeKey.id, values.resourceType, values.period)
    },
    enabled: !!scopeKey && !!values.scopeId,
  })

  const parentLimit = useMemo(() => {
    const q = parentQuotas?.quotas?.find((q) => q.resourceType === values.resourceType && q.period === values.period)
    return q?.effectiveLimit ?? null
  }, [parentQuotas, values.resourceType, values.period])

  const canCreateHere = values.scopeType === 'ORG'
    ? (auth.isPlatformAdmin || auth.hasSystemRole('BUDGET_OWNER') || auth.hasSystemRole('ORG_ADMIN'))
    : canCreateBudget(auth, values.scopeType, values.scopeId, search.scopeType === 'PROJECT' ? undefined : values.scopeId)

  const createMutation = useMutation({
    mutationFn: quotasApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets-dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['project-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['group-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['project-service-budgets'] })
      navigate({ to: '/budgets' })
    },
  })

  const handleSubmit = async () => {
    const limit = Number.parseFloat(values.limitAmount)
    if (!Number.isFinite(limit) || limit < 0) {
      createMutation.reset()
      return
    }

    if (parentLimit !== null && limit > parentLimit) {
      await dialogService.showConfirmDialog({
        title: 'Limit exceeds parent budget',
        message: `The limit ${formatAmount(limit, values.resourceType)} exceeds the parent effective limit ${formatAmount(parentLimit, values.resourceType)}. Please reduce the limit.`,
        type: 'warning',
        confirmLabel: 'OK',
        cancelLabel: '',
      })
      return
    }

    let tags: Record<string, unknown> | null = null
    if (values.tagsJson.trim()) {
      try {
        tags = JSON.parse(values.tagsJson) as Record<string, unknown>
      } catch {
        await dialogService.showConfirmDialog({
          title: 'Invalid tags JSON',
          message: 'Tags must be a valid JSON object.',
          type: 'warning',
          confirmLabel: 'OK',
          cancelLabel: '',
        })
        return
      }
    }

    const payload: CreateQuotaRequest = {
      scopeType: values.scopeType,
      scopeId: values.scopeId,
      resourceType: values.resourceType,
      period: values.period,
      limitAmount: limit,
      quotaType: values.scopeType === 'ORG' ? 'CEILING' : values.quotaType,
      enforcementMode: values.enforcementMode,
      serviceType: values.scopeType === 'SERVICE' ? (values.serviceType as QuotaServiceType) : undefined,
      maxExecutionAmount: values.maxExecutionAmount ? Number.parseFloat(values.maxExecutionAmount) : undefined,
      tags,
    }

    createMutation.mutate(payload)
  }

  return (
    <ContentAreaLayout maxWidth="40rem">
      <div className="space-y-6">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Link to="/budgets" className="hover:underline flex items-center gap-1">
            <ArrowLeft className="h-4 w-4" />
            Back to Budgets
          </Link>
        </div>

        <h1 className="text-2xl font-semibold tracking-tight">New Budget</h1>

        {!canCreateHere && (
          <Card className="border-destructive">
            <CardContent className="py-4 text-sm text-destructive">
              You do not have permission to create a budget for this scope.
            </CardContent>
          </Card>
        )}

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Create budget</CardTitle>
          </CardHeader>
          <CardContent>
            <BudgetForm
              values={values}
              onChange={setValues}
              onSubmit={handleSubmit}
              onCancel={() => navigate({ to: '/budgets' })}
              isLoading={createMutation.isPending}
              submitLabel="Create Budget"
              mode="create"
              parentScopeLabel={scopeKey ? (scopeKey.type === 'GROUP' ? 'Group' : 'Project') : undefined}
              parentLimit={parentLimit}
              error={createMutation.error instanceof Error ? createMutation.error.message : null}
            />
          </CardContent>
        </Card>
      </div>
    </ContentAreaLayout>
  )
}
