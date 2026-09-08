// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { describe, it, expect } from 'vitest'
import { validateWorkflowYaml, type WorkflowYamlContext } from './workflow-yaml-schema'

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

describe('validateWorkflowYaml', () => {
  it('accepts a minimal INFERENCE workflow', () => {
    expect(validateWorkflowYaml(INFERENCE_YAML, CTX).ok).toBe(true)
  })

  it('accepts a full ORCHESTRATOR workflow', () => {
    expect(validateWorkflowYaml(ORCHESTRATOR_YAML, CTX).ok).toBe(true)
  })

  it('rejects YAML parse errors with line info', () => {
    const v = validateWorkflowYaml('version: "1.0"\nworkflow: [', CTX)
    expect(v.ok).toBe(false)
    expect(v.issues[0]!.line).toBeGreaterThan(0)
  })

  it('rejects apiKey in models (UI variant)', () => {
    const v = validateWorkflowYaml(
      ORCHESTRATOR_YAML.replace(
        'models: [{ code: "glm-5.3" }]',
        'models:\n  - code: "glm-5.3"\n    apiKey: "sk-xxx"'
      ),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('apiKey'))).toBe(true)
  })

  it('rejects maxRetries on ORCHESTRATOR steps', () => {
    const v = validateWorkflowYaml(
      ORCHESTRATOR_YAML.replace(
        '    retryPolicy: { maxRetries: 2',
        '    maxRetries: 1\n    retryPolicy: { maxRetries: 2'
      ),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('maxRetries'))).toBe(true)
  })

  it('rejects orchestration on INFERENCE steps', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace(
        '    dependsOn: [generate]',
        '    dependsOn: [generate]\n    orchestration: { goal: "x" }'
      ),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('orchestration'))).toBe(true)
  })

  it('rejects duplicate step ids', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace('  - id: review', '  - id: generate'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('uplicate'))).toBe(true)
  })

  it('rejects missing dependsOn target', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace('dependsOn: [generate]', 'dependsOn: [nope]'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('nope'))).toBe(true)
  })

  it('rejects missing transition target', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace('transitions: { default: review }', 'transitions: { default: missing }'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('missing'))).toBe(true)
  })

  it('rejects dependency cycles', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace(
        '    prompt: "Do work"\n    dependsOn: []',
        '    prompt: "Do work"\n    dependsOn: [review]'
      ),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.toLowerCase().includes('cycle'))).toBe(true)
  })

  it('rejects mixed ORCHESTRATOR aliases', () => {
    const doubled = ORCHESTRATOR_YAML.replace(
      /$/,
      '\n  - id: second\n    name: Second\n    taskType: ORCHESTRATOR\n    agentProfileCode: fullstack\n    dependsOn: [backend]\n    retryPolicy: { maxRetries: 0, initialBackoffSeconds: 2, maxBackoffSeconds: 30 }\n    orchestration:\n      modelCode: "glm-5.3"\n      goal: "Second"\n      sourceSubPath: "app"\n      workers:\n        - { name: coder, modelCode: "glm-5.3", capability: "Implements", allowedTools: [read_file], allowedCommands: [] }\n      checkpointStrategy: { mode: ON_VERIFICATION_PASS, commitMessage: "feat: second", pushToRemote: false, allowNoChanges: false }\n      completionCriteria: { definitionOfDone: "done", requireVerificationBy: [coder] }\n      budget: { maxTokens: 1000, maxWorkerCalls: 10, maxVerifierRejectionsPerAttempt: 1, maxOrchestratorIterations: 5, maxWorkerIterations: 5, onBudgetExceeded: FAIL }'
    )
    const v = validateWorkflowYaml(doubled, CTX)
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('agentProfileCode'))).toBe(true)
  })

  it('rejects verifier not in workers', () => {
    const v = validateWorkflowYaml(
      ORCHESTRATOR_YAML.replace('requireVerificationBy: [coder]', 'requireVerificationBy: [ghost]'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('ghost'))).toBe(true)
  })

  it('rejects unknown engine model code in models', () => {
    const v = validateWorkflowYaml(
      ORCHESTRATOR_YAML.replace(/"glm-5\.3"/g, '"nope"'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('nope'))).toBe(true)
  })

  it('rejects referenced modelCode missing from models', () => {
    const v = validateWorkflowYaml(
      ORCHESTRATOR_YAML.replace('models: [{ code: "glm-5.3" }]', 'models: []'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('glm-5.3'))).toBe(true)
  })

  it('rejects unresolvable agentProfileCode', () => {
    const v = validateWorkflowYaml(
      INFERENCE_YAML.replace('agentProfileCode: fullstack', 'agentProfileCode: ghost'),
      CTX
    )
    expect(v.ok).toBe(false)
    expect(v.issues.some((i) => i.message.includes('ghost'))).toBe(true)
  })
})