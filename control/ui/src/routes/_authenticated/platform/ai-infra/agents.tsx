// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AgentsList } from '@/features/platform/ai-infra/agents/AgentsList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/agents')({
  component: AgentsList,
})
