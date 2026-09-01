// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Secret arrange helpers for E2E tests.
 */

import { ApiClient } from './api'

export interface SecretResponse {
  id: string
  name: string
  type: string
}

/**
 * Create a project-scoped secret of type SECRET_KEY.
 */
export async function createProjectSecret(
  api: ApiClient,
  projectId: string,
  name: string,
  secretValue: string,
): Promise<SecretResponse> {
  return api.request<SecretResponse>('POST', `/projects/${projectId}/secrets`, {
    name,
    type: 'SECRET_KEY',
    payload: { type: 'SECRET_KEY', secret: secretValue },
  })
}

/**
 * Create a global (org-scoped) secret of type SECRET_KEY.
 */
export async function createGlobalSecret(
  api: ApiClient,
  name: string,
  secretValue: string,
): Promise<SecretResponse> {
  return api.request<SecretResponse>('POST', '/admin/secrets', {
    name,
    type: 'SECRET_KEY',
    payload: { type: 'SECRET_KEY', secret: secretValue },
  })
}

/**
 * Delete a project-scoped secret.
 */
export async function deleteProjectSecret(
  api: ApiClient,
  projectId: string,
  secretId: string,
): Promise<void> {
  await api.request('DELETE', `/projects/${projectId}/secrets/${secretId}`)
}

/**
 * List project-scoped secrets.
 */
export async function listProjectSecrets(
  api: ApiClient,
  projectId: string,
): Promise<SecretResponse[]> {
  return api.request<SecretResponse[]>('GET', `/projects/${projectId}/secrets`)
}

/**
 * Get a project-scoped secret by ID.
 */
export async function getProjectSecret(
  api: ApiClient,
  projectId: string,
  secretId: string,
): Promise<SecretResponse> {
  return api.request<SecretResponse>('GET', `/projects/${projectId}/secrets/${secretId}`)
}

/**
 * Assert that a secret response does not contain the secret value.
 * Checks that no field named 'secret', 'value', 'payload', 'token', 'key', or 'password' exists.
 */
export function assertSecretMasked(secret: Record<string, unknown>): void {
  const sensitiveFields = ['secret', 'value', 'payload', 'token', 'key', 'password', 'privateKey', 'clientSecret']
  for (const field of sensitiveFields) {
    if (field in secret) {
      throw new Error(`Secret response contains sensitive field '${field}' — masking is broken`)
    }
  }
}