// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectAIContext } from '@/features/organization/projects/detail/ProjectAIContext'

export const Route = createFileRoute('/_authenticated/projects/$projectId/ai-context')({
  component: () => {
    const { projectId } = Route.useParams()
    return <ProjectAIContext projectId={projectId} />
  },
})
