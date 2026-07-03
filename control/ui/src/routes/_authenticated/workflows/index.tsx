// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import {
  WorkflowsList,
  validateWorkflowsSearch,
  type WorkflowsSearch,
} from '@/features/services/workflows/list/WorkflowsList'

export const Route = createFileRoute('/_authenticated/workflows/')({
  component: WorkflowsList,
  validateSearch: (search: Record<string, unknown>): WorkflowsSearch =>
    validateWorkflowsSearch(search),
})