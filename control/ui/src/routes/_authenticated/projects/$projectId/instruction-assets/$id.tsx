// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute, Link } from '@tanstack/react-router'
import { InstructionAssetDetail } from '@/features/platform/ai-context/instruction-assets/detail/InstructionAssetDetail'

export const Route = createFileRoute('/_authenticated/projects/$projectId/instruction-assets/$id')({
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
          <span className="text-foreground font-medium">Instruction Asset</span>
        </div>
        <InstructionAssetDetail assetId={id} projectId={projectId} />
      </div>
    )
  },
})