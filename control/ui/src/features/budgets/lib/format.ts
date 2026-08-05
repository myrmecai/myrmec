// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { QuotaResourceType } from '@/lib/api'

export function formatAmount(value: number | null | undefined, resourceType: QuotaResourceType): string {
  if (value === null || value === undefined) return '—'
  if (resourceType === 'COST_USD_CENTS') {
    return `$${(value / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  }
  return value.toLocaleString()
}

export function formatResourceType(resourceType: QuotaResourceType): string {
  switch (resourceType) {
    case 'COST_USD_CENTS':
      return 'Cost USD'
    case 'TOKENS':
      return 'Tokens'
    default:
      return resourceType
  }
}

export function formatPeriod(period: string): string {
  switch (period) {
    case 'DAILY':
      return 'Today'
    case 'MONTHLY_CALENDAR':
      return 'This month'
    case 'LIFETIME':
      return 'Lifetime'
    default:
      return period
  }
}

export function calculatePercentage(consumed: number | null | undefined, limit: number | null | undefined): number {
  if (!limit || consumed === null || consumed === undefined) return 0
  if (limit === 0) return 0
  return Math.min(100, Math.round((consumed / limit) * 100))
}

export function statusFromPercentage(percentage: number, paused: boolean): 'paused' | 'exceeded' | 'at-risk' | 'ok' {
  if (paused) return 'paused'
  if (percentage > 100) return 'exceeded'
  if (percentage >= 80) return 'at-risk'
  return 'ok'
}
