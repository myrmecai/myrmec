// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * GOV-01..GOV-06 — Governance profile API enforcement tests.
 *
 * Tests that the three built-in governance profiles (STRICT, STANDARD,
 * FLEXIBLE) correctly gate blocking features at org/project scope via
 * the REST API. These are pure API tests — the governance enforcement
 * happens at the service layer.
 */

import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import {
  setGovernanceProfile,
  resetGovernanceProfile,
  createOrgInstructionAssetInline,
  createProjectInstructionAssetInline,
  createExternalKnowledgeProvider,
  createDataFeed,
  expectGovernanceViolation,
} from '../helpers/governance'

test.beforeEach(async ({ api }) => {
  await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
})

test.afterEach(async ({ api }) => {
  await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
  await resetGovernanceProfile(api)
})

test.describe('GOV-01 — STRICT blocks inline instructions at org scope', () => {
  test('inline draft at org scope returns 403 GOVERNANCE_VIOLATION under STRICT', async ({ api }) => {
    await setGovernanceProfile(api, 'STRICT')
    const res = await createOrgInstructionAssetInline(api, `GOV-01 ${Date.now().toString(36)}`)
    expectGovernanceViolation(res, 'INSTRUCTION_SOURCES')
  })
})

test.describe('GOV-02 — STANDARD allows inline at project but not org', () => {
  test('inline draft at org scope returns 403 under STANDARD', async ({ api }) => {
    await setGovernanceProfile(api, 'STANDARD')
    const res = await createOrgInstructionAssetInline(api, `GOV-02 org ${Date.now().toString(36)}`)
    expectGovernanceViolation(res, 'INLINE_INSTRUCTIONS_SCOPE')
  })

  test('inline draft at project scope succeeds under STANDARD', async ({ api }) => {
    await setGovernanceProfile(api, 'STANDARD')
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `GOV-02 ${Date.now().toString(36)}` })
    const res = await createProjectInstructionAssetInline(api, project.id, `GOV-02 proj ${Date.now().toString(36)}`)
    expect(res.status).toBe(201)
  })
})

test.describe('GOV-03 — FLEXIBLE allows inline at all scopes', () => {
  test('inline draft at org scope succeeds under FLEXIBLE', async ({ api }) => {
    await setGovernanceProfile(api, 'FLEXIBLE')
    const res = await createOrgInstructionAssetInline(api, `GOV-03 org ${Date.now().toString(36)}`)
    expect(res.status).toBe(201)
  })

  test('inline draft at project scope succeeds under FLEXIBLE', async ({ api }) => {
    await setGovernanceProfile(api, 'FLEXIBLE')
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `GOV-03 ${Date.now().toString(36)}` })
    const res = await createProjectInstructionAssetInline(api, project.id, `GOV-03 proj ${Date.now().toString(36)}`)
    expect(res.status).toBe(201)
  })
})

test.describe('GOV-04 — STRICT blocks disallowed data-feed source types', () => {
  test('WEB_CRAWL data feed is blocked under STRICT', async ({ api }) => {
    await setGovernanceProfile(api, 'STRICT')
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `GOV-04 ${Date.now().toString(36)}` })
    const res = await createDataFeed(api, project.id, `GOV-04 feed`, 'WEB_CRAWL')
    expectGovernanceViolation(res, 'DATA_FEEDS')
  })
})

test.describe('GOV-05 — FLEXIBLE allows broader data-feed source types', () => {
  test('WEB_CRAWL data feed is not blocked under FLEXIBLE', async ({ api }) => {
    await setGovernanceProfile(api, 'FLEXIBLE')
    const project = await api.request<{ id: string }>('POST', '/projects', { name: `GOV-05 ${Date.now().toString(36)}` })
    const res = await createDataFeed(api, project.id, `GOV-05 feed`, 'WEB_CRAWL')
    // Governance passes; request may fail later for missing providerVersionId,
    // but it must NOT be a 403 GOVERNANCE_VIOLATION.
    expect(res.status).not.toBe(403)
    const body = res.body as { errorCode?: string }
    expect(body.errorCode).not.toBe('GOVERNANCE_VIOLATION')
  })
})

test.describe('GOV-06 — STRICT blocks EXTERNAL knowledge providers', () => {
  test('creating an EXTERNAL provider under STRICT returns 403 GOVERNANCE_VIOLATION', async ({ api }) => {
    await setGovernanceProfile(api, 'STRICT')
    const res = await createExternalKnowledgeProvider(api, `GOV-06 ${Date.now().toString(36)}`)
    expectGovernanceViolation(res, 'KNOWLEDGE_PROVIDERS')
  })
})