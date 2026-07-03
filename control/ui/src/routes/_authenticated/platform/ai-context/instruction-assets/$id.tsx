// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { InstructionAssetDetail } from '@/features/platform/ai-context/instruction-assets/detail/InstructionAssetDetail'

export const Route = createFileRoute('/_authenticated/platform/ai-context/instruction-assets/$id')({
  component: () => {
    const { id } = Route.useParams()
    return <InstructionAssetDetail assetId={id} />
  },
})
