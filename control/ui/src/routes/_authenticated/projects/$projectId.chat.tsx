// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectChat } from '@/features/organization/projects/detail/chat/ProjectChat'

export const Route = createFileRoute('/_authenticated/projects/$projectId/chat')({
  component: () => {
    const { projectId } = Route.useParams()
    return <ProjectChat projectId={projectId} />
  },
})
