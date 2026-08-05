// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { ChevronRight, ChevronDown, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import type { BudgetTreeNode, QuotaResourceType, QuotaScope } from '@/lib/api'
import { QuotaProgressBar } from './quota-progress-bar'
import { formatAmount, calculatePercentage, statusFromPercentage } from '../lib/format'

interface BudgetTreeProps {
  nodes: BudgetTreeNode[]
  resourceType: QuotaResourceType
  showFilter?: 'all' | 'at-risk' | 'exceeded' | 'paused'
}

function scopeRoute(scopeType: QuotaScope, scopeId: string): string {
  switch (scopeType) {
    case 'ORG':
      return '/budgets'
    case 'GROUP':
      return `/budgets/groups/${scopeId}`
    case 'PROJECT':
      return `/budgets/projects/${scopeId}`
    case 'SERVICE':
      return `/budgets/projects/${scopeId}/services`
    default:
      return '/budgets'
  }
}

function statusBadge(paused: boolean, percentage: number): string {
  const status = statusFromPercentage(percentage, paused)
  switch (status) {
    case 'paused':
      return 'Paused'
    case 'exceeded':
      return 'Exceeded'
    case 'at-risk':
      return 'At risk'
    default:
      return 'OK'
  }
}

function statusBadgeClass(paused: boolean, percentage: number): string {
  const status = statusFromPercentage(percentage, paused)
  switch (status) {
    case 'paused':
      return 'bg-amber-100 text-amber-800 hover:bg-amber-100'
    case 'exceeded':
      return 'bg-destructive/10 text-destructive hover:bg-destructive/10'
    case 'at-risk':
      return 'bg-amber-100 text-amber-800 hover:bg-amber-100'
    default:
      return 'bg-emerald-100 text-emerald-800 hover:bg-emerald-100'
  }
}

function TreeRow({
  node,
  resourceType,
  depth,
  showFilter,
}: {
  node: BudgetTreeNode
  resourceType: QuotaResourceType
  depth: number
  showFilter: 'all' | 'at-risk' | 'exceeded' | 'paused'
}) {
  const [expanded, setExpanded] = useState(depth < 2)
  const hasChildren = node.children && node.children.length > 0
  const percentage = calculatePercentage(node.consumed, node.effectiveLimit)
  const status = statusFromPercentage(percentage, node.paused)

  const filteredOut =
    showFilter !== 'all' && status !== (showFilter === 'at-risk' ? 'at-risk' : showFilter)

  const quotaLabel = node.quotaType
    ? `${node.quotaType === 'CEILING' ? 'Ceil.' : 'Res.'} ${formatAmount(node.effectiveLimit, resourceType)}`
    : `(inherits from ${node.scopeType.toLowerCase()} budget)`

  return (
    <>
      {!filteredOut && (
        <div
          className="grid grid-cols-12 gap-4 items-center px-4 py-2 border-b hover:bg-muted/50"
          style={{ paddingLeft: `${1 + depth * 1.5}rem` }}
        >
          <div className="col-span-4 flex items-center gap-2">
            {hasChildren ? (
              <button
                onClick={() => setExpanded((prev) => !prev)}
                className="p-0.5 rounded hover:bg-accent"
                aria-label={expanded ? 'Collapse' : 'Expand'}
              >
                {expanded ? (
                  <ChevronDown className="h-4 w-4 text-muted-foreground" />
                ) : (
                  <ChevronRight className="h-4 w-4 text-muted-foreground" />
                )}
              </button>
            ) : (
              <span className="w-5" />
            )}
            <Link
              to={scopeRoute(node.scopeType, node.scopeId)}
              className="font-medium hover:underline"
            >
              {node.name}
            </Link>
          </div>
          <div className="col-span-3 text-sm text-muted-foreground">{quotaLabel}</div>
          <div className="col-span-3">
            <QuotaProgressBar
              consumed={node.consumed}
              limit={node.effectiveLimit}
              paused={node.paused}
              size="sm"
            />
          </div>
          <div className="col-span-1">
            <Badge variant="secondary" className={statusBadgeClass(node.paused, percentage)}>
              {statusBadge(node.paused, percentage)}
            </Badge>
          </div>
          <div className="col-span-1 text-right">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/budgets">
                <Plus className="h-4 w-4" />
              </Link>
            </Button>
          </div>
        </div>
      )}
      {expanded && hasChildren &&
        node.children!.map((child) => (
          <TreeRow
            key={child.id}
            node={child}
            resourceType={resourceType}
            depth={depth + 1}
            showFilter={showFilter}
          />
        ))}
    </>
  )
}

export function BudgetTree({ nodes, resourceType, showFilter = 'all' }: BudgetTreeProps) {
  return (
    <div className="border rounded-lg overflow-hidden">
      <div className="grid grid-cols-12 gap-4 px-4 py-2 bg-muted font-medium text-sm border-b">
        <div className="col-span-4">Scope</div>
        <div className="col-span-3">Limit</div>
        <div className="col-span-3">Used</div>
        <div className="col-span-1">Status</div>
        <div className="col-span-1 text-right">Action</div>
      </div>
      {nodes.length === 0 ? (
        <div className="px-4 py-8 text-center text-muted-foreground">
          No budgets defined for this resource and period.
        </div>
      ) : (
        nodes.map((node) => (
          <TreeRow
            key={node.id}
            node={node}
            resourceType={resourceType}
            depth={0}
            showFilter={showFilter}
          />
        ))
      )}
    </div>
  )
}
