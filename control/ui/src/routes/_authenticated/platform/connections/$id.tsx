// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ConnectionDetail } from '@/features/platform/connections/detail/ConnectionDetail'

export const Route = createFileRoute('/_authenticated/platform/connections/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <ConnectionDetail configId={id} />
  },
})