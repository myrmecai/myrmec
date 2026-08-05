// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { QuotaResourceType, QuotaPeriod } from '@/lib/api'
import { formatPeriod as _formatPeriod } from '../lib/format'

interface BudgetFiltersProps {
  period: QuotaPeriod
  resourceType: QuotaResourceType
  show: 'all' | 'at-risk' | 'exceeded' | 'paused'
  onPeriodChange: (period: QuotaPeriod) => void
  onResourceTypeChange: (resourceType: QuotaResourceType) => void
  onShowChange: (show: 'all' | 'at-risk' | 'exceeded' | 'paused') => void
}

const PERIOD_OPTIONS: { value: QuotaPeriod; label: string }[] = [
  { value: 'DAILY', label: 'Today' },
  { value: 'MONTHLY_CALENDAR', label: 'This month' },
  { value: 'LIFETIME', label: 'Lifetime' },
]

const RESOURCE_OPTIONS: { value: QuotaResourceType; label: string }[] = [
  { value: 'COST_USD_CENTS', label: 'Cost USD' },
  { value: 'TOKENS', label: 'Tokens' },
]

const SHOW_OPTIONS: { value: 'all' | 'at-risk' | 'exceeded' | 'paused'; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'at-risk', label: 'At risk (>80%)' },
  { value: 'exceeded', label: 'Exceeded' },
  { value: 'paused', label: 'Paused' },
]

export function BudgetFilters({
  period,
  resourceType,
  show,
  onPeriodChange,
  onResourceTypeChange,
  onShowChange,
}: BudgetFiltersProps) {
  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="flex items-center gap-2">
        <span className="text-sm text-muted-foreground">Period:</span>
        <Select value={period} onValueChange={(v) => onPeriodChange(v as QuotaPeriod)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PERIOD_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex items-center gap-2">
        <span className="text-sm text-muted-foreground">Resource:</span>
        <Select value={resourceType} onValueChange={(v) => onResourceTypeChange(v as QuotaResourceType)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {RESOURCE_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex items-center gap-2">
        <span className="text-sm text-muted-foreground">Show:</span>
        <Select value={show} onValueChange={(v) => onShowChange(v as typeof show)}>
          <SelectTrigger className="w-[140px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SHOW_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}
