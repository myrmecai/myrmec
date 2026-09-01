// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Project arrange helpers for E2E tests.
 */

import { ApiClient } from './api'

/**
 * Create a project via the API.
 */
export async function createProject(
  api: ApiClient,
  name: string,
  description?: string,
): Promise<{ id: string; name: string }> {
  const body: Record<string, unknown> = { name }
  if (description) body.description = description
  const project = await api.request<{ id: string; name: string }>('POST', '/projects', body)
  return project
}