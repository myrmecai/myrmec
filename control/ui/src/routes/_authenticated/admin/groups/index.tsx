// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { GroupsList } from '@/features/organization/groups/GroupsList'

export const Route = createFileRoute('/_authenticated/admin/groups/')({
  component: GroupsList,
})
