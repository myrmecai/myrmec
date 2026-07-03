// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { DashboardPage } from '@/features/dashboard/DashboardPage'

export const Route = createFileRoute('/_authenticated/dashboard')({
  component: DashboardPage,
})
