// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { WorkflowRequests } from '@/features/services/workflows/detail/WorkflowRequests'

export const Route = createFileRoute('/_authenticated/workflows/$workflowId/requests')({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: () => {
    const { workflowId } = Route.useParams()
    return <WorkflowRequests workflowId={workflowId} />
  },
})