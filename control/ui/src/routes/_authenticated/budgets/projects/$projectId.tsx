// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { ProjectBudgetPage } from '@/features/budgets/ProjectBudgetPage'

export const Route = createFileRoute('/_authenticated/budgets/projects/$projectId')({
  component: RouteComponent,
})

function RouteComponent() {
  const { projectId } = Route.useParams()
  return <ProjectBudgetPage projectId={projectId} />
}
