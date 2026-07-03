// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { ProviderType } from '@/lib/api'

export const TYPE_LABELS: Record<ProviderType, string> = {
  MANAGED: 'Managed RAG',
  EXTERNAL: 'External API',
}

export const STATUS_COLORS: Record<string, string> = {
  INCOMPLETE: 'bg-yellow-500',
  ACTIVE: 'bg-green-500',
  DISABLED: 'bg-gray-500',
  ARCHIVED: 'bg-red-500',
}