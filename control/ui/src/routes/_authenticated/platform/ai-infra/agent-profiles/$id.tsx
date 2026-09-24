// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AgentProfileDetail } from '@/features/platform/ai-infra/agent-profiles/detail/AgentProfileDetail'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/agent-profiles/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <AgentProfileDetail profileId={id} />
  },
})