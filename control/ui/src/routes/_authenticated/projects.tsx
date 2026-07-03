// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { ProjectsLayout } from '@/features/organization/projects/list/ProjectsLayout'

export const Route = createFileRoute('/_authenticated/projects')({
  component: ProjectsLayout,
})
