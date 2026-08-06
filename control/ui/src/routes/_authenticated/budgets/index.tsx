// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { BudgetsDashboardPage } from '@/features/budgets/BudgetsDashboardPage'

export const Route = createFileRoute('/_authenticated/budgets/')({
  component: BudgetsDashboardPage,
})
