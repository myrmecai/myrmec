// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { GovernanceProfilesList } from '@/features/platform/ai-context/governance-profile/GovernanceProfilesList'

export const Route = createFileRoute('/_authenticated/platform/ai-context/governance-profile')({
  component: GovernanceProfilesList,
})