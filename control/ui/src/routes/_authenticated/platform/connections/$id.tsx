// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ConnectionDetail } from '@/features/platform/connections/detail/ConnectionDetail'

export const Route = createFileRoute('/_authenticated/platform/connections/$id')({
  validateSearch: (search: Record<string, unknown>) => ({
    edit: Boolean(search.edit) || false,
  }),
  component: () => {
    const { id } = Route.useParams()
    const { edit } = Route.useSearch()
    return <ConnectionDetail configId={id} initialEdit={edit} />
  },
})