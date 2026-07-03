// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectMembers } from '@/features/organization/projects/detail/ProjectMembers'

export const Route = createFileRoute('/_authenticated/projects/$projectId/members')({
  component: () => {
    const { projectId } = Route.useParams()
    return <ProjectMembers projectId={projectId} />
  },
})
