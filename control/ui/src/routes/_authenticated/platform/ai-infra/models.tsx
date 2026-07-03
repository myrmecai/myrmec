// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ModelsList } from '@/features/platform/ai-infra/models/ModelsList'

export const Route = createFileRoute('/_authenticated/platform/ai-infra/models')({
  component: ModelsList,
})