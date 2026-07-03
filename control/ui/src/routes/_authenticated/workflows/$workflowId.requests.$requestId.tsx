// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { RequestDetail } from '@/features/services/workflows/detail/RequestDetail'

export const Route = createFileRoute(
  '/_authenticated/workflows/$workflowId/requests/$requestId'
)({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: () => {
    const { workflowId, requestId } = Route.useParams()
    return <RequestDetail workflowId={workflowId} requestId={requestId} />
  },
})