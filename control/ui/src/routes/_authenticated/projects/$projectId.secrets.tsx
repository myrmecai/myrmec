// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectSecrets } from '@/features/organization/projects/detail/ProjectSecrets'

export const Route = createFileRoute('/_authenticated/projects/$projectId/secrets')({
  component: () => {
    const { projectId } = Route.useParams()
    return <ProjectSecrets projectId={projectId} />
  },
})
