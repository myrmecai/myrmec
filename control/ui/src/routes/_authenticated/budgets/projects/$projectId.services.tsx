// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { ServiceBudgetsPage } from '@/features/budgets/ServiceBudgetsPage'

export const Route = createFileRoute('/_authenticated/budgets/projects/$projectId/services')({
  component: RouteComponent,
})

function RouteComponent() {
  const { projectId } = Route.useParams()
  return <ServiceBudgetsPage projectId={projectId} />
}
