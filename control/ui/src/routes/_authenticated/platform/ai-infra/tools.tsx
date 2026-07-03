// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ToolsList } from '@/features/platform/ai-infra/tools/ToolsList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/tools')({
  component: ToolsList,
})