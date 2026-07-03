// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AssistantsList, validateAssistantsSearch, type AssistantsSearch } from '@/features/services/assistants/list/AssistantsList'

export const Route = createFileRoute('/_authenticated/assistants/')({
  validateSearch: (search: Record<string, unknown>): AssistantsSearch => validateAssistantsSearch(search),
  component: AssistantsList,
})
