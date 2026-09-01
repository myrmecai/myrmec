// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

// NOTE: The string keys in STATUS_COLORS and TYPE_LABELS must stay in sync
// with the values in @/lib/domain-constants (EntityStatus, ConnectionType).
// They are kept as plain string keys here because they are used as object
// literal keys in a Record map — computed property names would complicate
// the type annotations without meaningful benefit.

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
  GIT: [],
  HTTP: [
    { key: 'testEndpoint', label: 'Test Endpoint', placeholder: '/health', type: 'text' },
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