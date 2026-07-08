// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectDetail } from '@/features/organization/projects/detail/ProjectDetail'

export const Route = createFileRoute('/_authenticated/projects/$projectId')({
  component: () => {
    const { projectId } = Route.useParams()
    return <ProjectDetail projectId={projectId} />
  },
})