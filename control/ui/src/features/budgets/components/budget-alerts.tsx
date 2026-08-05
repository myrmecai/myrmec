// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { AlertTriangle, Ban } from 'lucide-react'
import { Link } from '@tanstack/react-router'
import type { BudgetTreeNode, QuotaResourceType } from '@/lib/api'
import { calculatePercentage, statusFromPercentage, formatAmount, formatResourceType } from '../lib/format'

interface BudgetAlertsProps {
  nodes: BudgetTreeNode[]
  resourceType: QuotaResourceType
}

interface AlertItem {
  id: string
  scopeId: string
  scopeType: string
  name: string
  message: string
  severity: 'warning' | 'danger'
}

function collectAlerts(nodes: BudgetTreeNode[], resourceType: QuotaResourceType): AlertItem[] {
  const alerts: AlertItem[] = []

  const walk = (node: BudgetTreeNode) => {
    const percentage = calculatePercentage(node.consumed, node.effectiveLimit)
    const status = statusFromPercentage(percentage, node.paused)

    if (node.paused) {
      alerts.push({
        id: `${node.id}-paused`,
        scopeId: node.scopeId,
        scopeType: node.scopeType,
        name: node.name,
        message: `${node.name} budget is paused`,
        severity: 'warning',
      })
    } else if (status === 'exceeded') {
      alerts.push({
        id: `${node.id}-exceeded`,
        scopeId: node.scopeId,
        scopeType: node.scopeType,
        name: node.name,
        message: `${node.name} exceeded ${formatResourceType(resourceType)} budget (${formatAmount(node.consumed, resourceType)} / ${formatAmount(node.effectiveLimit, resourceType)})`,
        severity: 'danger',
      })
    } else if (status === 'at-risk') {
      alerts.push({
        id: `${node.id}-risk`,
        scopeId: node.scopeId,
        scopeType: node.scopeType,
        name: node.name,
        message: `${node.name} at ${percentage}% of ${formatResourceType(resourceType)} budget`,
        severity: 'warning',
      })
    }

    node.children?.forEach(walk)
  }

  nodes.forEach(walk)
  return alerts
}

function alertRoute(scopeType: string, scopeId: string): string {
  if (scopeType === 'GROUP') return `/budgets/groups/${scopeId}`
  if (scopeType === 'PROJECT') return `/budgets/projects/${scopeId}`
  if (scopeType === 'SERVICE') return `/budgets/projects/${scopeId}/services`
  return '/budgets'
}

export function BudgetAlerts({ nodes, resourceType }: BudgetAlertsProps) {
  const alerts = collectAlerts(nodes, resourceType)

  if (alerts.length === 0) {
    return null
  }

  return (
    <div className="space-y-2">
      {alerts.slice(0, 5).map((alert) => (
        <Link
          key={alert.id}
          to={alertRoute(alert.scopeType, alert.scopeId)}
          className="flex items-start gap-3 p-3 rounded-lg border hover:bg-muted/50 transition-colors"
        >
          {alert.severity === 'danger' ? (
            <Ban className="h-5 w-5 text-destructive shrink-0 mt-0.5" />
          ) : (
            <AlertTriangle className="h-5 w-5 text-amber-500 shrink-0 mt-0.5" />
          )}
          <span className="text-sm">{alert.message}</span>
        </Link>
      ))}
    </div>
  )
}
