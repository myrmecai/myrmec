// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProvidersList } from '@/features/platform/ai-infra/providers/ProvidersList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/providers')({
  component: ProvidersList,
})