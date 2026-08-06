// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { EditBudgetPage } from '@/features/budgets/EditBudgetPage'

export const Route = createFileRoute('/_authenticated/budgets/edit/$quotaId')({
  component: EditBudgetPage,
})
