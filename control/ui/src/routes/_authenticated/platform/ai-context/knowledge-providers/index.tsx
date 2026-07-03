// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { KnowledgeProvidersList } from '@/features/platform/ai-context/knowledge-providers/list/KnowledgeProvidersList'

export const Route = createFileRoute('/_authenticated/platform/ai-context/knowledge-providers/')({
  component: KnowledgeProvidersList,
})
