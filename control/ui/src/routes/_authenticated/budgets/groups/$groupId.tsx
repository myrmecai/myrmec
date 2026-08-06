// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { GroupBudgetPage } from '@/features/budgets/GroupBudgetPage'

export const Route = createFileRoute('/_authenticated/budgets/groups/$groupId')({
  component: RouteComponent,
})

function RouteComponent() {
  const { groupId } = Route.useParams()
  return <GroupBudgetPage groupId={groupId} />
}
