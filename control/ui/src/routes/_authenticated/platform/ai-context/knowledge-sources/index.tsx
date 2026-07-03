// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { KnowledgeSourcesList } from '@/features/platform/ai-context/knowledge-sources/list/KnowledgeSourcesList'

export const Route = createFileRoute('/_authenticated/platform/ai-context/knowledge-sources/')({
  component: KnowledgeSourcesList,
})