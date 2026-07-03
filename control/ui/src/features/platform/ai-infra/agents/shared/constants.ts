// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import type { AgentWorkerStatus } from '@/lib/api'

export const WORKER_STATUS_STYLES: Record<AgentWorkerStatus, string> = {
  IDLE: 'bg-slate-500',
  RESERVED: 'bg-amber-500',
  CONNECTING: 'bg-blue-500',
  BOUND: 'bg-green-600',
  DRAINING: 'bg-orange-500',
  DEAD: 'bg-red-600',
}