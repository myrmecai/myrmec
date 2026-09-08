// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { z } from 'zod'
import YAML from 'yaml'

/**
 * The UI YAML authoring variant (design §6 superset, spec
 * docs/superpowers/specs/2026-09-08-workflow-yaml-authoring-design.md).
 * INFERENCE steps carry the engine's inference fields; ORCHESTRATOR steps
 * keep the §6 orchestration shape verbatim. The engine's bare-`maxRetries`
 * compatibility stays engine-only: ORCHESTRATOR steps use `retryPolicy`.
 * Credential fields (`apiKey`, `apiEndpoint`) are rejected — engine model
 * rows own credentials.
 *
 * This schema is deliberately UI-owned (Zod 3) rather than shared with the
 * agents' Zod 4 `workflowDefinitionSchema`: the UI variant covers both step
 * types and resolves codes against engine rows.
 */

const STEP_ID_PATTERN = /^[a-zA-Z0-9_-]+$/
const STEP_ID = z
  .string()
  .min(1, 'Step ID is required.')
  .max(50, 'Step ID must be 50 characters or fewer.')
  .regex(STEP_ID_PATTERN, 'Use letters, digits, hyphens or underscores only.')

const agentProfileCode = z
  .string()
  .min(1, 'agentProfileCode is required.')

const inferenceStepSchema = z
  .object({
    id: STEP_ID,
    name: z.string().min(1, 'Name is required.').max(100),
    taskType: z.literal('INFERENCE').optional(),
    agentProfileCode,
    prompt: z.string().max(50_000).optional(),
    dependsOn: z.array(z.string()).default([]),
    transitions: z.record(z.string()).optional(),
    timeoutSeconds: z.number().int().min(1).max(86_400).optional(),
    maxRetries: z.number().int().min(0).max(10).optional(),
    pauseMode: z.enum(['NONE', 'BEFORE', 'AFTER', 'BOTH']).default('NONE'),
  })
  .strict()

const retryPolicySchema = z
  .object({
    maxRetries: z.number().int().min(0).max(10).default(0),
    initialBackoffSeconds: z.number().int().min(1).default(2),
    maxBackoffSeconds: z.number().int().min(1).default(60),
  })
  .strict()

const workerSchema = z
  .object({
    name: z.string().min(1),
    modelCode: z.string().min(1),
    capability: z.string().min(1),
    allowedTools: z.array(
      z.enum(['read_file', 'list_directory', 'create_directory', 'write_file', 'execute_command'])
    ),
    allowedCommands: z.array(z.string()).default([]),
  })
  .strict()

const orchestrationSchema = z
  .object({
    modelCode: z.string().min(1),
    goal: z.string().min(1),
    specPath: z.string().nullable().default(null),
    sourceSubPath: z.string().min(1).default('.'),
    workers: z.array(workerSchema).min(1),
    checkpointStrategy: z
      .object({
        mode: z.literal('ON_VERIFICATION_PASS'),
        commitMessage: z.string().min(1).max(72),
        pushToRemote: z.boolean(),
        allowNoChanges: z.boolean(),
      })
      .strict(),
    completionCriteria: z
      .object({
        definitionOfDone: z.string().min(1),
        requireVerificationBy: z.array(z.string()),
      })
      .strict(),
    budget: z
      .object({
        maxTokens: z.number().int().min(1),
        maxWorkerCalls: z.number().int().min(1),
        maxVerifierRejectionsPerAttempt: z.number().int().min(0),
        maxOrchestratorIterations: z.number().int().min(1),
        maxWorkerIterations: z.number().int().min(1),
        onBudgetExceeded: z.enum(['FAIL', 'PAUSE_FOR_HUMAN_REVIEW']),
      })
      .strict(),
  })
  .strict()

const orchestratorStepSchema = z
  .object({
    id: STEP_ID,
    name: z.string().min(1, 'Name is required.').max(100),
    taskType: z.literal('ORCHESTRATOR'),
    agentProfileCode,
    dependsOn: z.array(z.string()).default([]),
    retryPolicy: retryPolicySchema,
    orchestration: orchestrationSchema,
  })
  .strict()

const stepSchema = z.discriminatedUnion('taskType', [
  inferenceStepSchema,
  orchestratorStepSchema,
])

const modelEntrySchema = z
  .object({
    code: z.string().min(1),
    modelId: z.string().optional(),
    description: z.string().optional(),
  })
  .strict()

const sourceSchema = z
  .object({
    repoUrl: z.string().min(1),
    sourceBranch: z.string().optional(),
    targetBranch: z.string().optional(),
  })
  .strict()

export const uiWorkflowYamlDocSchema = z
  .object({
    version: z.literal('1.0'),
    id: z.string().optional(),
    name: z.string().min(1, 'Name is required.').max(120),
    models: z.array(modelEntrySchema).optional(),
    source: sourceSchema.optional(),
    workflow: z.array(stepSchema).min(1, 'At least one step is required.'),
  })
  .strict()

export type InferenceStepYaml = z.infer<typeof inferenceStepSchema>
export type OrchestratorStepYaml = z.infer<typeof orchestratorStepSchema>
export type WorkflowYamlStep = z.infer<typeof stepSchema>
export type WorkflowYamlDoc = z.infer<typeof uiWorkflowYamlDocSchema>

/** One authoring problem shown in the issues panel. */
export interface YamlIssue {
  code: 'YAML_PARSE' | 'SCHEMA' | 'CROSS'
  message: string
  line?: number
  stepId?: string
}

export interface WorkflowYamlValidation {
  ok: boolean
  issues: YamlIssue[]
  /** The parsed document when schema-valid (cross checks already passed). */
  doc?: WorkflowYamlDoc
}

/** Resolution context for authoring codes. */
export interface WorkflowYamlContext {
  /** Resolvable agent-profile codes → UUID. */
  profiles: Record<string, string>
  /** Existing engine model codes. */
  models: Set<string>
}

/** Parse + schema-check the YAML text. Line info from the `yaml` parser. */
function parseAndSchema(text: string): { issues: YamlIssue[]; doc?: WorkflowYamlDoc } {
  let raw: unknown
  try {
    raw = YAML.parse(text)
  } catch (e) {
    const err = e as {
      message?: string
      linePos?: Array<{ line: number; col: number }> | [number, number] | null
    }
    let line: number | undefined
    if (Array.isArray(err.linePos)) {
      const first = err.linePos[0]
      line = typeof first === 'number' ? first : first?.line
    }
    return {
      issues: [
        {
          code: 'YAML_PARSE',
          message: `YAML parse error: ${err.message?.split('\n')[0] ?? 'invalid YAML'}`,
          line,
        },
      ],
    }
  }
  const result = uiWorkflowYamlDocSchema.safeParse(raw)
  if (result.success) {
    return { issues: [], doc: result.data }
  }
  const issues: YamlIssue[] = result.error.issues.map((zi) => {
    const stepIndex = zi.path.findIndex((p) => p === 'workflow' && false)
    // path shape: ['workflow', <index>, <field>...]
    const wfPos = zi.path.indexOf('workflow')
    let stepId: string | undefined
    let line: number | undefined
    if (wfPos >= 0 && typeof zi.path[wfPos + 1] === 'number') {
      const idx = zi.path[wfPos + 1] as number
      const steps = (raw as { workflow?: unknown[] })?.workflow
      if (Array.isArray(steps) && steps[idx] && typeof steps[idx] === 'object') {
        stepId = String((steps[idx] as Record<string, unknown>).id ?? '')
        if (!stepId) stepId = undefined
      }
    }
    const field = zi.path[zi.path.length - 1]
    const fieldText = typeof field === 'string' && field !== 'workflow' ? `'${field}'` : ''
    const prefix = stepId ? `step '${stepId}': ` : fieldText ? `${fieldText}: ` : ''
    void stepIndex
    return {
      code: 'SCHEMA' as const,
      message: `${prefix}${zi.message}`,
      stepId,
      line,
    }
  })
  return { issues }
}

/** DFS cycle detection over dependsOn. */
function findCycle(steps: WorkflowYamlStep[]): string | null {
  const ids = new Set(steps.map((s) => s.id))
  const graph = new Map<string, string[]>()
  for (const s of steps) {
    graph.set(s.id, s.dependsOn.filter((d) => ids.has(d)))
  }
  const visiting = new Set<string>()
  const done = new Set<string>()
  const visit = (id: string, chain: string[]): string[] | null => {
    if (done.has(id)) return null
    if (visiting.has(id)) return [...chain, id]
    visiting.add(id)
    for (const dep of graph.get(id) ?? []) {
      const cycle = visit(dep, [...chain, id])
      if (cycle) return cycle
    }
    visiting.delete(id)
    done.add(id)
    return null
  }
  for (const s of steps) {
    const cycle = visit(s.id, [])
    if (cycle) return cycle.join(' → ')
  }
  return null
}

/**
 * Full live validation (spec §4.2 tier 1): YAML parse, per-step schema,
 * then the cross-step rules. Save is gated on `ok`.
 */
export function validateWorkflowYaml(
  text: string,
  ctx: WorkflowYamlContext
): WorkflowYamlValidation {
  const { issues, doc } = parseAndSchema(text)
  if (!doc) {
    return { ok: false, issues }
  }

  const cross: YamlIssue[] = []
  const steps = doc.workflow
  const ids = steps.map((s) => s.id)

  // 1. duplicate step ids
  const seen = new Set<string>()
  for (const s of steps) {
    if (seen.has(s.id)) {
      cross.push({ code: 'CROSS', message: `Duplicate step id '${s.id}'.`, stepId: s.id })
    }
    seen.add(s.id)
  }

  // 2. dependsOn targets exist
  const idSet = new Set(ids)
  for (const s of steps) {
    for (const dep of s.dependsOn) {
      if (!idSet.has(dep)) {
        cross.push({
          code: 'CROSS',
          message: `Step '${s.id}' depends on unknown step '${dep}'.`,
          stepId: s.id,
        })
      }
    }
  }

  // 3. transition targets exist (INFERENCE steps)
  for (const s of steps) {
    if (s.taskType === 'ORCHESTRATOR') continue
    for (const target of Object.values(s.transitions ?? {})) {
      if (!idSet.has(target)) {
        cross.push({
          code: 'CROSS',
          message: `Step '${s.id}' transitions to unknown step '${target}'.`,
          stepId: s.id,
        })
      }
    }
  }

  // 4. dependency cycles
  const cycle = findCycle(steps)
  if (cycle) {
    cross.push({ code: 'CROSS', message: `Dependency cycle: ${cycle}.` })
  }

  // 5. one identical agentProfileCode across ORCHESTRATOR steps
  const orchestrators = steps.filter((s) => s.taskType === 'ORCHESTRATOR')
  const aliases = new Set(orchestrators.map((s) => s.agentProfileCode))
  if (aliases.size > 1) {
    cross.push({
      code: 'CROSS',
      message: `All ORCHESTRATOR steps must share one agentProfileCode (found: ${[...aliases].join(', ')}).`,
    })
  }

  // 6. verifiers ⊆ workers
  for (const s of orchestrators) {
    const workerNames = new Set(s.orchestration.workers.map((w) => w.name))
    for (const verifier of s.orchestration.completionCriteria.requireVerificationBy) {
      if (!workerNames.has(verifier)) {
        cross.push({
          code: 'CROSS',
          message: `Step '${s.id}': verifier '${verifier}' is not in the worker catalog.`,
          stepId: s.id,
        })
      }
    }
  }

  // 7. referenced model codes ∈ models[]
  const declared = new Set((doc.models ?? []).map((m) => m.code))
  for (const s of orchestrators) {
    const referenced = [s.orchestration.modelCode, ...s.orchestration.workers.map((w) => w.modelCode)]
    for (const code of referenced) {
      if (!declared.has(code)) {
        cross.push({
          code: 'CROSS',
          message: `Step '${s.id}' references model '${code}' missing from the models catalog.`,
          stepId: s.id,
        })
      }
    }
  }

  // 8. models[].code exists as an engine model row
  for (const m of doc.models ?? []) {
    if (!ctx.models.has(m.code)) {
      cross.push({
        code: 'CROSS',
        message: `Model '${m.code}' does not exist as an engine model row.`,
      })
    }
  }

  // 9. agentProfileCode resolvable
  for (const s of steps) {
    if (!(s.agentProfileCode in ctx.profiles)) {
      cross.push({
        code: 'CROSS',
        message: `Step '${s.id}': agentProfileCode '${s.agentProfileCode}' does not match an active agent profile.`,
        stepId: s.id,
      })
    }
  }

  return { ok: issues.length === 0 && cross.length === 0, issues: [...issues, ...cross], doc }
}