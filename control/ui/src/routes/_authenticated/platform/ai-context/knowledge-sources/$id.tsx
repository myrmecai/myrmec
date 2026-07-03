// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { KnowledgeSourceDetail } from '@/features/platform/ai-context/knowledge-sources/detail/KnowledgeSourceDetail'

export const Route = createFileRoute('/_authenticated/platform/ai-context/knowledge-sources/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <KnowledgeSourceDetail sourceId={id} />
  },
})