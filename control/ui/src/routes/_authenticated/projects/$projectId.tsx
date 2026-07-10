// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute, Outlet, useMatch } from '@tanstack/react-router'
import { ProjectDetail } from '@/features/organization/projects/detail/ProjectDetail'

export const Route = createFileRoute('/_authenticated/projects/$projectId')({
  component: ProjectDetailLayout,
})

function ProjectDetailLayout() {
  const { projectId } = Route.useParams()

  // Check if any child route is active (chat, instruction-assets/$id, knowledge-providers/$id)
  const chatMatch = useMatch({
    from: '/_authenticated/projects/$projectId/chat',
    shouldThrow: false,
  })
  const instructionAssetMatch = useMatch({
    from: '/_authenticated/projects/$projectId/instruction-assets/$id',
    shouldThrow: false,
  })
  const knowledgeProviderMatch = useMatch({
    from: '/_authenticated/projects/$projectId/knowledge-providers/$id',
    shouldThrow: false,
  })

  if (chatMatch || instructionAssetMatch || knowledgeProviderMatch) {
    return <Outlet />
  }

  return <ProjectDetail projectId={projectId} />
}