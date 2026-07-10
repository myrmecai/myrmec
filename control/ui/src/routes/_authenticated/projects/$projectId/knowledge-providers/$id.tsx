// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute, Link } from '@tanstack/react-router'
import { KnowledgeProviderDetail } from '@/features/platform/ai-context/knowledge-providers/detail/KnowledgeProviderDetail'

export const Route = createFileRoute('/_authenticated/projects/$projectId/knowledge-providers/$id')({
  component: () => {
    const { id, projectId } = Route.useParams()
    return (
      <div>
        <div className="flex items-center gap-2 mb-4 text-sm text-muted-foreground">
          <Link to="/projects/$projectId" params={{ projectId }} className="hover:underline">
            Project
          </Link>
          <span>/</span>
          <Link to="/projects/$projectId" params={{ projectId }} className="hover:underline">
            AI Context
          </Link>
          <span>/</span>
          <span className="text-foreground font-medium">Knowledge Provider</span>
        </div>
        <KnowledgeProviderDetail providerId={id} projectId={projectId} />
      </div>
    )
  },
})