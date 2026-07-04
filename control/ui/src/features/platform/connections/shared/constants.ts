// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { ConnectionType, CredentialType } from '@/lib/api'

export const TYPE_LABELS: Record<ConnectionType, string> = {
  GIT: 'Git Repository',
  HTTP: 'HTTP API',
  S3: 'S3 Storage',
  DB: 'Database',
}

export const STATUS_COLORS: Record<string, string> = {
  INCOMPLETE: 'bg-yellow-500',
  ACTIVE: 'bg-green-500',
  DISABLED: 'bg-gray-500',
  ARCHIVED: 'bg-red-500',
}

/**
 * Allowed credential secret types per connection type (UC-018 §5).
 */
export const ALLOWED_SECRET_TYPES: Record<ConnectionType, CredentialType[]> = {
  GIT: ['USERNAME_PASSWORD', 'BEARER_TOKEN', 'API_KEY', 'SSL_PRIVATE_KEY'],
  HTTP: ['USERNAME_PASSWORD', 'BEARER_TOKEN', 'API_KEY', 'SSL_PRIVATE_KEY'],
  DB: ['USERNAME_PASSWORD'],
  S3: ['USERNAME_PASSWORD'],
}

/**
 * Type-specific config field definitions (UC-018 §5).
 * Each field maps to a key in the version `config` JSONB column.
 */
export interface ConfigField {
  key: string
  label: string
  placeholder?: string
  type: 'text' | 'number' | 'select'
  options?: string[]
}

export const TYPE_CONFIG_FIELDS: Record<ConnectionType, ConfigField[]> = {
  GIT: [
    { key: 'ref', label: 'Ref', placeholder: 'main' },
  ],
  HTTP: [
    { key: 'headers', label: 'Headers (JSON)', placeholder: '{"X-Custom":"value"}', type: 'text' },
    { key: 'timeoutMs', label: 'Timeout (ms)', placeholder: '30000', type: 'number' },
  ],
  DB: [
    { key: 'dbProvider', label: 'DB Provider', type: 'select', options: ['PostgreSQL'] },
  ],
  S3: [
    { key: 'bucket', label: 'Bucket', placeholder: 'my-bucket' },
    { key: 'region', label: 'Region', placeholder: 'us-east-1' },
    { key: 'prefix', label: 'Prefix', placeholder: 'data/' },
  ],
}