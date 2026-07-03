// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { DataFeedsList } from '@/features/platform/ai-context/data-feeds/list/DataFeedsList'

export const Route = createFileRoute('/_authenticated/platform/ai-context/data-feeds/')({
  component: DataFeedsList,
})
