// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { cn } from '@/lib/utils'
import { calculatePercentage, statusFromPercentage } from '../lib/format'

interface QuotaProgressBarProps {
  consumed: number | null | undefined
  limit: number | null | undefined
  paused?: boolean
  showPercentage?: boolean
  size?: 'sm' | 'md'
  className?: string
}

export function QuotaProgressBar({
  consumed,
  limit,
  paused = false,
  showPercentage = true,
  size = 'md',
  className,
}: QuotaProgressBarProps) {
  const percentage = calculatePercentage(consumed, limit)
  const status = statusFromPercentage(percentage, paused)

  const statusClasses = {
    paused: 'bg-amber-500',
    exceeded: 'bg-destructive',
    'at-risk': 'bg-amber-500',
    ok: 'bg-emerald-500',
  }

  const height = size === 'sm' ? 'h-1.5' : 'h-2'

  return (
    <div className={cn('flex items-center gap-3', className)}>
      <div className={cn('flex-1 rounded-full bg-muted overflow-hidden', height)}>
        <div
          className={cn('h-full transition-all', statusClasses[status])}
          style={{ width: `${percentage}%` }}
          aria-hidden="true"
        />
      </div>
      {showPercentage && (
        <span className="text-xs text-muted-foreground w-10 text-right tabular-nums">
          {paused ? '⛔' : `${percentage}%`}
        </span>
      )}
    </div>
  )
}
