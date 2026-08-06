// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState, useEffect, useMemo } from 'react'
import { useNavigate, useParams, Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { quotasApi, budgetsApi } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { dialogService } from '@/services/dialog-service'
import { BudgetForm, type BudgetFormValues } from './components/budget-form'
import { canMutateBudget } from './lib/budget-permissions'
import { formatAmount } from './lib/format'

export function EditBudgetPage() {
  const { quotaId } = useParams({ from: '/_authenticated/budgets/edit/$quotaId' })
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const auth = useAuth()

  const { data: quota, isLoading, error, refetch } = useQuery({
    queryKey: ['quota', quotaId],
    queryFn: () => quotasApi.get(quotaId),
  })

  const scopeKey = useMemo(() => {
    if (!quota) return null
    if (quota.scopeType === 'SERVICE') return { type: 'PROJECT' as const, id: quota.scopeId }
    if (quota.scopeType === 'PROJECT') return { type: 'PROJECT' as const, id: quota.scopeId }
    if (quota.scopeType === 'GROUP') return { type: 'GROUP' as const, id: quota.scopeId }
    return null
  }, [quota])

  const { data: parentQuotas } = useQuery({
    queryKey: ['effective-quotas', scopeKey?.type, scopeKey?.id, quota?.resourceType, quota?.period],
    queryFn: () => {
      if (!scopeKey || !quota) return Promise.resolve({ quotas: [] })
      if (scopeKey.type === 'PROJECT') {
        return budgetsApi.projectEffectiveQuotas(scopeKey.id, quota.resourceType, quota.period)
      }
      return budgetsApi.groupEffectiveQuotas(scopeKey.id, quota.resourceType, quota.period)
    },
    enabled: !!scopeKey && !!quota,
  })

  const parentLimit = useMemo(() => {
    if (!quota) return null
    const q = parentQuotas?.quotas?.find((q) => q.resourceType === quota.resourceType && q.period === quota.period)
    return q?.effectiveLimit ?? null
  }, [parentQuotas, quota])

  const canEdit = canMutateBudget(auth, quota?.scopeType ?? 'ORG', quota?.scopeId ?? '')

  const [values, setValues] = useState<BudgetFormValues | null>(null)

  useEffect(() => {
    if (!quota) return
    setValues({
      scopeType: quota.scopeType,
      scopeId: quota.scopeId,
      resourceType: quota.resourceType,
      period: quota.period,
      limitAmount: String(quota.limitAmount),
      quotaType: quota.quotaType,
      enforcementMode: quota.enforcementMode,
      maxExecutionAmount: quota.maxExecutionAmount != null ? String(quota.maxExecutionAmount) : '',
      serviceType: quota.serviceType ?? '',
      tagsJson: quota.tags ? JSON.stringify(quota.tags, null, 2) : '',
    })
  }, [quota])

  const updateMutation = useMutation({
    mutationFn: (payload: Parameters<typeof quotasApi.update>[1]) => quotasApi.update(quotaId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets-dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['project-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['group-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['project-service-budgets'] })
      if (quota?.scopeType === 'PROJECT') {
        navigate({ to: '/budgets/projects/$projectId', params: { projectId: quota.scopeId } })
      } else if (quota?.scopeType === 'GROUP') {
        navigate({ to: '/budgets/groups/$groupId', params: { groupId: quota.scopeId } })
      } else {
        navigate({ to: '/budgets' })
      }
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => quotasApi.delete(quotaId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['budgets-dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['project-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['group-effective-quotas'] })
      queryClient.invalidateQueries({ queryKey: ['project-service-budgets'] })
      navigate({ to: '/budgets' })
    },
  })

  const handleDelete = async () => {
    const confirmed = await dialogService.showConfirmDialog({
      title: 'Delete budget',
      message: 'Are you sure you want to delete this budget? This action cannot be undone.',
      type: 'warning',
      severity: 'warning',
      confirmLabel: 'Delete',
      cancelLabel: 'Cancel',
    })
    if (confirmed) deleteMutation.mutate()
  }

  const handleSubmit = async () => {
    if (!values || !quota) return
    const limit = Number.parseFloat(values.limitAmount)
    if (!Number.isFinite(limit) || limit < 0) return

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

    updateMutation.mutate({
      limitAmount: limit,
      enforcementMode: values.enforcementMode,
      maxExecutionAmount: values.maxExecutionAmount ? Number.parseFloat(values.maxExecutionAmount) : undefined,
      tags,
    })
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

        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight">Edit Budget</h1>
          {canEdit && quota && (
            <Button variant="destructive" size="sm" onClick={handleDelete} disabled={deleteMutation.isPending}>
              <Trash2 className="h-4 w-4 mr-2" />
              Delete
            </Button>
          )}
        </div>

        {isLoading ? (
          <div className="space-y-4">
            <Skeleton className="h-8 w-48" />
            <Skeleton className="h-64 w-full" />
          </div>
        ) : error || !quota || !values ? (
          <Card className="border-destructive">
            <CardHeader>
              <CardTitle className="text-destructive text-base">Failed to load budget</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{error instanceof Error ? error.message : 'Budget not found.'}</p>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => refetch()}>Retry</Button>
                <Button variant="outline" onClick={() => navigate({ to: '/budgets' })}>Go to Budgets</Button>
              </div>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Update budget</CardTitle>
            </CardHeader>
            <CardContent>
              <BudgetForm
                values={values}
                onChange={setValues}
                onSubmit={handleSubmit}
                onCancel={() => navigate({ to: '/budgets' })}
                isLoading={updateMutation.isPending}
                submitLabel="Save Changes"
                mode="edit"
                parentScopeLabel={scopeKey ? (scopeKey.type === 'GROUP' ? 'Group' : 'Project') : undefined}
                parentLimit={parentLimit}
                error={updateMutation.error instanceof Error ? updateMutation.error.message : null}
              />
            </CardContent>
          </Card>
        )}
      </div>
    </ContentAreaLayout>
  )
}
