// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { CheckCircle, XCircle } from 'lucide-react'

/** Active/Inactive status badge for agent profiles (shared list + detail). */
export function StatusBadge({ status }: { status: 'ACTIVE' | 'INACTIVE' }) {
  if (status === 'ACTIVE') {
    return (
      <span className="inline-flex items-center gap-1 text-green-600 text-sm">
        <CheckCircle className="h-4 w-4" />
        Active
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1 text-muted-foreground text-sm">
      <XCircle className="h-4 w-4" />
      Inactive
    </span>
  )
}