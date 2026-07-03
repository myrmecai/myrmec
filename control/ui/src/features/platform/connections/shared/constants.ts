// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { ConnectionType } from '@/lib/api'

export const TYPE_LABELS: Record<ConnectionType, string> = {
  GIT: 'Git Repository',
  HTTP: 'HTTP API',
  MANAGED_RAG: 'Managed RAG',
  S3: 'S3 Storage',
  DB_SCHEMA: 'Database Schema',
}

export const STATUS_COLORS: Record<string, string> = {
  INCOMPLETE: 'bg-yellow-500',
  ACTIVE: 'bg-green-500',
  DISABLED: 'bg-gray-500',
  ARCHIVED: 'bg-red-500',
}