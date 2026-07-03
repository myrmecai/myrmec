// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { Outlet, useMatch } from '@tanstack/react-router'
import { ProjectsList } from '@/features/organization/projects/list/ProjectsList'

export function ProjectsLayout() {
  const secretsMatch = useMatch({
    from: '/_authenticated/projects/$projectId/secrets',
    shouldThrow: false,
  })
  const membersMatch = useMatch({
    from: '/_authenticated/projects/$projectId/members',
    shouldThrow: false,
  })
  const chatMatch = useMatch({
    from: '/_authenticated/projects/$projectId/chat',
    shouldThrow: false,
  })
  const aiContextMatch = useMatch({
    from: '/_authenticated/projects/$projectId/ai-context',
    shouldThrow: false,
  })
  if (secretsMatch || membersMatch || chatMatch || aiContextMatch) {
    return <Outlet />
  }

  return <ProjectsList />
}