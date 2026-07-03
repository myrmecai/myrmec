// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { DataFeedDetail } from '@/features/platform/ai-context/data-feeds/detail/DataFeedDetail'

export const Route = createFileRoute('/_authenticated/platform/ai-context/data-feeds/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <DataFeedDetail feedId={id} />
  },
})