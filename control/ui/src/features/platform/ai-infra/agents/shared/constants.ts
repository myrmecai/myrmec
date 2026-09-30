// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import type { AgentStatus } from '@/lib/api'

export const AGENT_STATUS_STYLES: Record<AgentStatus, string> = {
  IDLE: 'bg-slate-500',
  RESERVED: 'bg-amber-500',
  CONNECTING: 'bg-blue-500',
  BOUND: 'bg-green-600',
  DRAINING: 'bg-orange-500',
  DEAD: 'bg-red-600',
}