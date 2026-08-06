// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import type { EffectiveQuota, QuotaResourceType, QuotaPeriod } from '@/lib/api'
import { formatAmount, formatResourceType, formatPeriod, calculatePercentage, statusFromPercentage } from '../lib/format'
import { QuotaProgressBar } from './quota-progress-bar'
import { Pencil, Pause, Play, Trash2 } from 'lucide-react'

interface EffectiveQuotaCardProps {
  quota: EffectiveQuota
  resourceType: QuotaResourceType
  period: QuotaPeriod
  canMutate: boolean
  groupId?: string
  onEdit?: (id: string) => void
  onPause: (id: string) => void
  onResume: (id: string) => void
  onDelete: (id: string) => void
}

function statusLabel(paused: boolean, percentage: number): string {
  const status = statusFromPercentage(percentage, paused)
  switch (status) {
    case 'paused': return 'Paused'
    case 'exceeded': return 'Exceeded'
    case 'at-risk': return 'At risk'
    default: return 'OK'
  }
}

export function EffectiveQuotaCard({
  quota,
  resourceType,
  period,
  canMutate,
  groupId: _groupId,
  onEdit,
  onPause,
  onResume,
  onDelete,
}: EffectiveQuotaCardProps) {
  const percentage = calculatePercentage(quota.consumed, quota.effectiveLimit)
  const status = statusFromPercentage(percentage, quota.paused)

  return (
    <Card className={quota.exceeded ? 'border-destructive' : undefined}>
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between">
          <div>
            <CardTitle className="text-base font-medium">
              {formatPeriod(period)} {formatResourceType(resourceType).toLowerCase()} budget
            </CardTitle>
            <div className="flex items-center gap-2 mt-1 text-sm text-muted-foreground">
              {quota.quotaType ? (
                <>
                  <span>Type: {quota.quotaType === 'CEILING' ? 'Ceiling' : 'Reservation'}</span>
                  {quota.enforcementMode && <span>Mode: {quota.enforcementMode}</span>}
                </>
              ) : (
                <span>Inherited from ancestor</span>
              )}
            </div>
          </div>
          <Badge
            variant="secondary"
            className={
              status === 'paused' ? 'bg-amber-100 text-amber-800' :
              status === 'exceeded' ? 'bg-destructive/10 text-destructive' :
              status === 'at-risk' ? 'bg-amber-100 text-amber-800' :
              'bg-emerald-100 text-emerald-800'
            }
          >
            {quota.exceeded ? 'Exceeded' : statusLabel(quota.paused, percentage)}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="text-lg font-semibold" data-testid="effective-limit">
          {formatAmount(quota.effectiveLimit, resourceType)}
        </div>
        <QuotaProgressBar consumed={quota.consumed} limit={quota.effectiveLimit} paused={quota.paused} />

        <div className="flex items-center justify-between text-sm text-muted-foreground">
          <span>
            {formatAmount(quota.consumed ?? 0, resourceType)} / {formatAmount(quota.effectiveLimit, resourceType)}
            {quota.remaining !== null && quota.remaining !== undefined && (
              <span className="ml-2">Remaining {formatAmount(quota.remaining, resourceType)}</span>
            )}
          </span>
          {quota.atRisk && <span>At risk</span>}
        </div>

        {canMutate && quota.id && (
          <div className="flex items-center gap-2 pt-2">
            <Button variant="outline" size="sm" aria-label="Edit" onClick={() => onEdit?.(quota.id)}>
              <Pencil className="h-3.5 w-3.5 mr-1" />
              Edit
            </Button>
            {quota.paused ? (
              <Button variant="outline" size="sm" onClick={() => onResume(quota.id)} aria-label="Resume">
                <Play className="h-3.5 w-3.5 mr-1" />
                Resume
              </Button>
            ) : (
              <Button variant="outline" size="sm" onClick={() => onPause(quota.id)} aria-label="Pause">
                <Pause className="h-3.5 w-3.5 mr-1" />
                Pause
              </Button>
            )}
            <Button variant="outline" size="sm" className="text-destructive" onClick={() => onDelete(quota.id)} aria-label="Delete">
              <Trash2 className="h-3.5 w-3.5 mr-1" />
              Delete
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
