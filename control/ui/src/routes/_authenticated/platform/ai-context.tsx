// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { PlatformGroupLayout } from '@/components/platform-group-layout'

export const Route = createFileRoute('/_authenticated/platform/ai-context')({
  component: () => (
    <PlatformGroupLayout
      title="AI Context Management"
      items={[
        { label: 'Instruction Assets', to: '/platform/ai-context/instruction-assets' },
        { label: 'Knowledge Providers', to: '/platform/ai-context/knowledge-providers' },
        { label: 'Knowledge Sources', to: '/platform/ai-context/knowledge-sources' },
        { label: 'Governance Profile', to: '/platform/ai-context/governance-profile' },
      ]}
    />
  ),
})