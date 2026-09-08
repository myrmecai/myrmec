// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { yamlToWorkflow, workflowToYaml } from './workflow-yaml-convert'
import type { WorkflowYamlContext } from './workflow-yaml-schema'

const CTX: WorkflowYamlContext = {
  profiles: {
    fullstack: '11111111-1111-4111-8111-111111111111',
    'governed-coding': '22222222-2222-4222-8222-222222222222',
  },
  models: new Set(['glm-5.3']),
}

const INFERENCE_YAML = `
version: "1.0"
id: "wf"
name: "WF"
workflow:
  - id: generate
    name: Generate
    agentProfileCode: fullstack
    prompt: "Do work"
    dependsOn: []
    transitions: { default: review }
    timeoutSeconds: 600
    maxRetries: 2
    pauseMode: NONE
  - id: review
    name: Review
    agentProfileCode: fullstack
    dependsOn: [generate]
`

const ORCHESTRATOR_YAML = `
version: "1.0"
id: "wf"
name: "WF"
models: [{ code: "glm-5.3" }]
source: { repoUrl: "https://x.git", sourceBranch: "main", targetBranch: "feat/x" }
workflow:
  - id: backend
    name: Backend
    taskType: ORCHESTRATOR
    agentProfileCode: governed-coding
    dependsOn: []
    retryPolicy: { maxRetries: 2, initialBackoffSeconds: 2, maxBackoffSeconds: 30 }
    orchestration:
      modelCode: "glm-5.3"
      goal: "Build it"
      sourceSubPath: "app"
      workers:
        - { name: coder, modelCode: "glm-5.3", capability: "Implements", allowedTools: [read_file, write_file], allowedCommands: [] }
      checkpointStrategy: { mode: ON_VERIFICATION_PASS, commitMessage: "feat: x", pushToRemote: false, allowNoChanges: false }
      completionCriteria: { definitionOfDone: "done", requireVerificationBy: [coder] }
      budget: { maxTokens: 1000, maxWorkerCalls: 10, maxVerifierRejectionsPerAttempt: 1, maxOrchestratorIterations: 5, maxWorkerIterations: 5, onBudgetExceeded: FAIL }
`

describe('yamlToWorkflow', () => {
  it('compiles INFERENCE steps with resolved UUIDs', () => {
    const out = yamlToWorkflow(INFERENCE_YAML, CTX)
    expect(out.steps[0]).toMatchObject({
      id: 'generate',
      agentProfileId: CTX.profiles.fullstack,
      taskType: 'INFERENCE',
      pauseMode: 'NONE',
    })
    expect(out.steps[0]!.orchestration).toBeUndefined()
    expect(out.steps[0]!.retryPolicy).toBeUndefined()
  })

  it('compiles ORCHESTRATOR steps with alias injection + bindings', () => {
    const out = yamlToWorkflow(ORCHESTRATOR_YAML, CTX)
    const step = out.steps[0]!
    expect(step.taskType).toBe('ORCHESTRATOR')
    expect((step.orchestration as Record<string, unknown>).agentProfileCode).toBe('governed-coding')
    expect(out.bindings).toEqual({ 'governed-coding': CTX.profiles['governed-coding']! })
    expect(step.retryPolicy).toEqual({ maxRetries: 2, initialBackoffSeconds: 2, maxBackoffSeconds: 30 })
  })

  it('maps source to artifactsRepo', () => {
    const out = yamlToWorkflow(ORCHESTRATOR_YAML, CTX)
    expect(out.artifactsRepo).toEqual({ url: 'https://x.git', baseBranch: 'main' })
    expect(out.defaultTargetBranch).toBe('feat/x')
  })

  it('throws on invalid YAML with joined issues', () => {
    expect(() => yamlToWorkflow('version: "1.0"\nworkflow: []', CTX)).toThrow(/at least/i)
  })
})

describe('workflowToYaml ∘ yamlToWorkflow round-trip', () => {
  it('round-trips the INFERENCE document losslessly', () => {
    const compiled = yamlToWorkflow(INFERENCE_YAML, CTX)
    const yText = workflowToYaml({
      name: 'WF',
      steps: compiled.steps,
      artifactsRepo: compiled.artifactsRepo,
      profiles: CTX.profiles,
    })
    const round = yamlToWorkflow(yText, CTX)
    expect(round.steps).toEqual(compiled.steps)
  })

  it('round-trips the ORCHESTRATOR document losslessly (bindings included)', () => {
    const compiled = yamlToWorkflow(ORCHESTRATOR_YAML, CTX)
    const yText = workflowToYaml({
      name: 'WF',
      steps: compiled.steps,
      artifactsRepo: compiled.artifactsRepo,
      bindings: compiled.bindings,
      defaultTargetBranch: compiled.defaultTargetBranch,
      profiles: CTX.profiles,
    })
    const round = yamlToWorkflow(yText, CTX)
    expect(round.steps).toEqual(compiled.steps)
    expect(round.bindings).toEqual(compiled.bindings)
    expect(round.defaultTargetBranch).toBe(compiled.defaultTargetBranch)
  })

  it('treats engine nulls as absent fields (prompt: null etc.)', () => {
    // The engine JSON echoes unset optional fields as explicit nulls
    // (e.g. a step authored without a prompt). The UI YAML variant's
    // fields are optional(), not nullable — the converter must drop
    // them instead of emitting `prompt: null`.
    const yText = workflowToYaml({
      name: 'WF',
      steps: [
        {
          id: 'review',
          name: 'Review Step',
          agentProfileId: Object.values(CTX.profiles)[0],
          prompt: null,
          dependsOn: [],
          transitions: {},
          timeoutSeconds: 300,
          maxRetries: 0,
          pauseMode: 'NONE',
          taskType: 'INFERENCE',
        },
      ],
      profiles: CTX.profiles,
    })
    expect(yText).not.toMatch(/prompt:/)
    expect(yText).not.toMatch(/transitions:/)
    const round = yamlToWorkflow(yText, CTX)
    expect(round.steps[0].prompt).toBeUndefined()
  })
})

describe('addressbook fixture import', () => {
  it('imports the canonical e2e addressbook YAML cleanly', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const fixturePath = resolve(
      here,
      '../../../../../myrmec-ee/e2e/fixtures/orchestration/09-addressbook.yaml'
    )
    const raw = readFileSync(fixturePath, 'utf-8')
    // The e2e authoring dialect carries credential/env fields the UI
    // variant rejects (engine rows own credentials). Import strips them
    // by parse→drop→re-serialize: provider/apiEndpoint/apiKey/parameters
    // on models, accessToken on source. The steps — the actual workflow
    // contract — are untouched.
    const parsed = YAML.parse(raw) as {
      models?: Array<Record<string, unknown>>
      source?: Record<string, unknown>
      workflow: unknown[]
    }
    for (const m of parsed.models ?? []) {
      delete m.provider
      delete m.apiEndpoint
      delete m.apiKey
      delete m.parameters
    }
    if (parsed.source) delete parsed.source.accessToken
    const text = YAML.stringify(parsed, { lineWidth: 0 })
    const ctx: WorkflowYamlContext = {
      profiles: { 'governed-coding': '22222222-2222-4222-8222-222222222222' },
      models: new Set(['orch-model', 'worker-model']),
    }
    const out = yamlToWorkflow(text, ctx)
    expect(out.steps).toHaveLength(3)
    expect(out.steps.every((s) => s.taskType === 'ORCHESTRATOR')).toBe(true)
    expect(out.bindings).toEqual({ 'governed-coding': '22222222-2222-4222-8222-222222222222' })
  })
})