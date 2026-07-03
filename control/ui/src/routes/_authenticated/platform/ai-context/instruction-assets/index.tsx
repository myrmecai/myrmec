// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute } from '@tanstack/react-router'
import { InstructionAssetsList } from '@/features/platform/ai-context/instruction-assets/list/InstructionAssetsList'

export const Route = createFileRoute('/_authenticated/platform/ai-context/instruction-assets/')({
  component: InstructionAssetsList,
})
