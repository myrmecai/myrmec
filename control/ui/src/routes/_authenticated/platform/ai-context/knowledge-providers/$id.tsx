// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { KnowledgeProviderDetail } from '@/features/platform/ai-context/knowledge-providers/detail/KnowledgeProviderDetail'

export const Route = createFileRoute('/_authenticated/platform/ai-context/knowledge-providers/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <KnowledgeProviderDetail providerId={id} />
  },
})
