// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { NewBudgetPage } from '@/features/budgets/NewBudgetPage'

export const Route = createFileRoute('/_authenticated/budgets/new')({
  component: NewBudgetPage,
})
