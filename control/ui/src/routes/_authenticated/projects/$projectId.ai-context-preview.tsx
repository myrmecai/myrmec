// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { EffectiveContextPreviewPage } from '@/features/organization/projects/detail/EffectiveContextPreviewPage'

export const Route = createFileRoute('/_authenticated/projects/$projectId/ai-context-preview')({
  component: () => {
    const { projectId } = Route.useParams()
    return <EffectiveContextPreviewPage projectId={projectId} />
  },
})