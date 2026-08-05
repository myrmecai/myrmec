// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { QuotaResourceType, BudgetTreeNode } from '@/lib/api'
import { formatAmount, formatResourceType } from '../lib/format'

interface BudgetSummaryCardsProps {
  tree: BudgetTreeNode[]
  resourceType: QuotaResourceType
}

export function BudgetSummaryCards({ tree, resourceType }: BudgetSummaryCardsProps) {
  // Sum only root-level scopes to avoid double-counting inherited child limits.
  const totalLimit = tree.reduce((sum, node) => sum + (node.effectiveLimit ?? 0), 0)
  const totalConsumed = tree.reduce((sum, node) => sum + (node.consumed ?? 0), 0)

  const remaining = Math.max(0, totalLimit - totalConsumed)

  const cards = [
    { label: `Total ${formatResourceType(resourceType)} limit`, value: formatAmount(totalLimit, resourceType) },
    { label: 'Consumed', value: formatAmount(totalConsumed, resourceType) },
    { label: 'Remaining', value: formatAmount(remaining, resourceType) },
  ]

  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
      {cards.map((card) => (
        <Card key={card.label}>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">{card.label}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-semibold">{card.value}</div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
