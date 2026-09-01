// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Global teardown for E2E tests.
 *
 * Resets the governance profile to STANDARD as a safety net, in case
 * a test that changes the profile fails before its afterEach runs.
 */
import { ApiClient } from './helpers/api'
import { E2E_ADMIN } from './helpers/api'
import { resetGovernanceProfile } from './helpers/governance'

export default async function globalTeardown() {
  const api = new ApiClient()
  try {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    await resetGovernanceProfile(api)
  } catch {
    // Best-effort cleanup; don't fail the run if the server is already down.
  }
}