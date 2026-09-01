// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Workflow arrange helpers for E2E tests.
 *
 * Creates workflows via the API for cross-UC arrange (e.g. seeding a workflow
 * for RBAC tests). For workflow-CRUD tests, drive the UI per the golden rule.
 */

import { ApiClient } from './api'

export interface WorkflowStep {
  id: string
  name: string
  agentProfileId: string
  prompt?: string
  dependsOn?: string[]
  maxRetries?: number
}

export interface CreatedWorkflow {
  id: string
  name: string
  status: string
}

/**
 * Create a workflow via the API.
 * The project must allow WORKFLOW service type.
 * An agent profile must be provided for each step.
 */
export async function createWorkflow(
  api: ApiClient,
  projectId: string,
  name: string,
  steps: WorkflowStep[],
  description?: string,
): Promise<CreatedWorkflow> {
  const body: Record<string, unknown> = {
    projectId,
    name,
    steps: steps.map((s) => ({
      id: s.id,
      name: s.name,
      agentProfileId: s.agentProfileId,
      prompt: s.prompt ?? 'Test step',
      dependsOn: s.dependsOn ?? [],
      maxRetries: s.maxRetries ?? 0,
    })),
  }
  if (description) body.description = description

  return api.request<CreatedWorkflow>('POST', `/projects/${projectId}/workflows`, body)
}

/**
 * Publish a workflow via the API.
 */
export async function publishWorkflow(
  api: ApiClient,
  projectId: string,
  workflowId: string,
): Promise<CreatedWorkflow> {
  return api.request<CreatedWorkflow>('POST', `/projects/${projectId}/workflows/${workflowId}/publish`)
}

/**
 * Archive a workflow via the API.
 */
export async function archiveWorkflow(
  api: ApiClient,
  projectId: string,
  workflowId: string,
): Promise<CreatedWorkflow> {
  return api.request<CreatedWorkflow>('POST', `/projects/${projectId}/workflows/${workflowId}/archive`)
}

/**
 * Delete a workflow via the API.
 */
export async function deleteWorkflow(
  api: ApiClient,
  projectId: string,
  workflowId: string,
): Promise<void> {
  await api.request('DELETE', `/projects/${projectId}/workflows/${workflowId}`)
}

/**
 * Get a workflow by ID.
 */
export async function getWorkflow(
  api: ApiClient,
  projectId: string,
  workflowId: string,
): Promise<CreatedWorkflow> {
  return api.request<CreatedWorkflow>('GET', `/projects/${projectId}/workflows/${workflowId}`)
}

/**
 * List workflows for a project.
 */
export async function listWorkflows(
  api: ApiClient,
  projectId: string,
): Promise<CreatedWorkflow[]> {
  return api.request<CreatedWorkflow[]>('GET', `/projects/${projectId}/workflows`)
}

/**
 * Create an agent profile and return its ID.
 * Used by workflow tests that need a profile for step assignment.
 */
export async function createAgentProfileForWorkflow(
  api: ApiClient,
  name: string,
): Promise<string> {
  const profile = await api.request<{ id: string }>('POST', '/admin/agent-profiles', {
    name,
    description: 'Workflow test profile',
    capabilities: [],
    supportedTools: [],
    toolCodes: [],
    systemPrompt: 'You are a workflow test agent.',
    defaultModel: 'github-gpt-4o',
  })
  return profile.id
}