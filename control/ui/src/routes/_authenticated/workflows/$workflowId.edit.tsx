// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { WorkflowEditor } from '@/features/services/workflows/detail/WorkflowEditor'

export const Route = createFileRoute('/_authenticated/workflows/$workflowId/edit')({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: () => {
    const { workflowId } = Route.useParams()
    return <WorkflowEditor workflowId={workflowId} />
  },
})