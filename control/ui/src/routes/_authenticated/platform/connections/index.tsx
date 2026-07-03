// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ConnectionsList } from '@/features/platform/connections/list/ConnectionsList'

export const Route = createFileRoute('/_authenticated/platform/connections/')({
  component: ConnectionsList,
})