// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AssistantEditor } from '@/features/services/assistants/detail/AssistantEditor'

export const Route = createFileRoute('/_authenticated/assistants/$assistantId/edit')({
  component: () => {
    const { assistantId } = Route.useParams()
    return <AssistantEditor assistantId={assistantId} />
  },
})
