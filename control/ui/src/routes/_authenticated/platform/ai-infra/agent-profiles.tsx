// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AgentProfilesList } from '@/features/platform/ai-infra/agent-profiles/AgentProfilesList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/agent-profiles')({
  component: AgentProfilesList,
})
