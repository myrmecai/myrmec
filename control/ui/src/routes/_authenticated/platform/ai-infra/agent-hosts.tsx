// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AgentHostsList } from '@/features/platform/ai-infra/agents/AgentHostsList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/agent-hosts')({
  component: AgentHostsList,
})