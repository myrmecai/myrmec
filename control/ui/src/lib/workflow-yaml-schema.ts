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
  .describe('Unique step ID; letters, digits, hyphens or underscores.')

const agentProfileCode = z
  .string()
  .min(1, 'agentProfileCode is required.')
  .describe('Agent profile code that runs this step.')

const inferenceStepSchema = z
  .object({
    id: STEP_ID,
    name: z.string().min(1, 'Name is required.').max(100).describe('Display name of the step.'),
    taskType: z.literal('INFERENCE').optional().describe('Leave INFERENCE for model-call steps.'),
    agentProfileCode,
    prompt: z.string().max(50_000).optional().describe('Prompt sent to the agent profile.'),
    dependsOn: z.array(z.string()).default([]).describe('Step IDs that must run before this step.'),
    transitions: z
      .record(z.string())
      .optional()
      .describe('Map of transition names to next step IDs.'),
    timeoutSeconds: z
      .number()
      .int()
      .min(1)
      .max(86_400)
      .optional()
      .describe('Step timeout in seconds (1 to 86400).'),
    maxRetries: z
      .number()
      .int()
      .min(0)
      .max(10)
      .optional()
      .describe('Retry attempts on failure (0 to 10).'),
    pauseMode: z
      .enum(['NONE', 'BEFORE', 'AFTER', 'BOTH'])
      .default('NONE')
      .describe('Pause the run before, after, or around this step.'),
  })
  .strict()

const retryPolicySchema = z
  .object({
    maxRetries: z.number().int().min(0).max(10).default(0).describe('Retry attempts (0 to 10).'),
    initialBackoffSeconds: z
      .number()
      .int()
      .min(1)
      .default(2)
      .describe('First retry backoff in seconds.'),
    maxBackoffSeconds: z
      .number()
      .int()
      .min(1)
      .default(60)
      .describe('Upper bound for retry backoff in seconds.'),
  })
  .strict()
  .describe('Retry behavior for the orchestrator step.')

const helperSchema = z
  .object({
    name: z.string().min(1).describe('Helper name; verifiers refer to it.'),
    modelCode: z.string().min(1).describe('Model code from the models catalog.'),
    capability: z.string().min(1).describe('Short description of what this helper does.'),
    allowedTools: z
      .array(
        z.enum(['read_file', 'list_directory', 'create_directory', 'write_file', 'execute_command'])
      )
      .describe('File and command tools this helper may use.'),
    allowedCommands: z
      .array(z.string())
      .default([])
      .describe('Shell commands this helper may execute.'),
  })
  .strict()

const orchestrationSchema = z
  .object({
    modelCode: z.string().min(1).describe('Model code for the orchestrator.'),
    goal: z.string().min(1).describe('What the orchestrator should achieve.'),
    specPath: z.string().nullable().default(null).describe('Repo-relative path to the spec file.'),
    sourceSubPath: z.string().min(1).default('.').describe('Subdirectory the work applies to.'),
    helpers: z.array(helperSchema).min(1).describe('Helper catalog for this step.'),
    checkpointStrategy: z
      .object({
        mode: z.literal('ON_VERIFICATION_PASS').describe('When to commit checkpoints.'),
        commitMessage: z
          .string()
          .min(1)
          .max(72)
          .describe('Commit message used for checkpoints (max 72 chars).'),
        pushToRemote: z.boolean().describe('Push checkpoint commits to the remote.'),
        allowNoChanges: z.boolean().describe('Accept a checkpoint when nothing changed.'),
      })
      .strict()
      .describe('How checkpoints are committed.'),
    completionCriteria: z
      .object({
        definitionOfDone: z.string().min(1).describe('Conditions that finish the task.'),
        requireVerificationBy: z
          .array(z.string())
          .describe('Helper names that must verify completion.'),
      })
      .strict()
      .describe('What counts as done and who verifies it.'),
    budget: z
      .object({
        maxTokens: z.number().int().min(1).describe('Maximum total tokens for the step.'),
        maxWorkerCalls: z.number().int().min(1).describe('Maximum number of worker calls.'),
        maxVerifierRejectionsPerAttempt: z
          .number()
          .int()
          .min(0)
          .describe('Verifier rejections allowed per attempt.'),
        maxOrchestratorIterations: z
          .number()
          .int()
          .min(1)
          .describe('Maximum orchestrator loop iterations.'),
        maxWorkerIterations: z.number().int().min(1).describe('Maximum worker loop iterations.'),
        onBudgetExceeded: z
          .enum(['FAIL', 'PAUSE_FOR_HUMAN_REVIEW'])
          .describe('What happens when the budget is exhausted.'),
      })
      .strict()
      .describe('Spending and iteration limits for this step.'),
  })
  .strict()

const orchestratorStepSchema = z
  .object({
    id: STEP_ID,
    name: z.string().min(1, 'Name is required.').max(100).describe('Display name of the step.'),
    taskType: z.literal('ORCHESTRATOR').describe('Set ORCHESTRATOR for agentic steps.'),
    agentProfileCode,
    dependsOn: z.array(z.string()).default([]).describe('Step IDs that must run before this step.'),
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
    code: z.string().min(1).describe('Model code referenced by orchestration steps.'),
    modelId: z.string().optional().describe('Engine model identifier for this entry.'),
    description: z.string().optional().describe('Human-readable note about this model.'),
  })
  .strict()

const sourceSchema = z
  .object({
    repoUrl: z.string().min(1).describe('Repository URL the workflow operates on.'),
    sourceBranch: z.string().optional().describe('Branch the workflow reads from.'),
    targetBranch: z.string().optional().describe('Branch the workflow writes results to.'),
  })
  .strict()

export const uiWorkflowYamlDocSchema = z
  .object({
    version: z.literal('1.0').describe('Document schema version; keep 1.0.'),
    id: z.string().optional().describe('Workflow identifier; leave unset for new workflows.'),
    name: z.string().min(1, 'Name is required.').max(120).describe('Display name of the workflow.'),
    models: z.array(modelEntrySchema).optional().describe('Model codes available to this workflow.'),
    source: sourceSchema.optional().describe('Source repository and branches.'),
    workflow: z
      .array(stepSchema)
      .min(1, 'At least one step is required.')
      .describe('Ordered list of steps to execute.'),
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

  // 6. verifiers ⊆ helpers
  for (const s of orchestrators) {
    const helperNames = new Set(s.orchestration.helpers.map((w) => w.name))
    for (const verifier of s.orchestration.completionCriteria.requireVerificationBy) {
      if (!helperNames.has(verifier)) {
        cross.push({
          code: 'CROSS',
          message: `Step '${s.id}': verifier '${verifier}' is not in the helper catalog.`,
          stepId: s.id,
        })
      }
    }
  }

  // 7. referenced model codes ∈ models[]
  const declared = new Set((doc.models ?? []).map((m) => m.code))
  for (const s of orchestrators) {
    const referenced = [s.orchestration.modelCode, ...s.orchestration.helpers.map((w) => w.modelCode)]
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