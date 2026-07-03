// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { UsersList } from '@/features/organization/users/UsersList'

export const Route = createFileRoute('/_authenticated/users')({
  component: UsersList,
})
