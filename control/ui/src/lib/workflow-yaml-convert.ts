// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import YAML from 'yaml'
import type { ArtifactsRepo, WorkflowStep } from '@/lib/api'
import {
  validateWorkflowYaml,
  type WorkflowYamlContext,
  type WorkflowYamlDoc,
  type WorkflowYamlStep,
} from './workflow-yaml-schema'

/**
 * Client-side compile of the UI YAML variant to the engine's existing
 * workflow contract (spec §4.1): agentProfileCode → UUID, the
 * ORCHESTRATOR alias injected into the orchestration map (engine
 * validator requirement), `source:` → the existing artifactsRepo field,
 * and the alias→UUID bindings map for the /orchestration-bindings POST.
 * Mirrors the proven e2e EngineScenarioAdapter choreography.
 */

export interface CompiledWorkflow {
  steps: WorkflowStep[]
  artifactsRepo?: ArtifactsRepo
  /** alias → profile UUID; defined when any ORCHESTRATOR step exists */
  bindings?: Record<string, string>
  /** default target branch for the Run dialog (from source.targetBranch) */
  defaultTargetBranch?: string
}

/** Compile YAML text; throws with the joined validation issues when invalid. */
export function yamlToWorkflow(text: string, ctx: WorkflowYamlContext): CompiledWorkflow {
  const validation = validateWorkflowYaml(text, ctx)
  if (!validation.ok || !validation.doc) {
    throw new Error(
      validation.issues.length > 0
        ? validation.issues.map((i) => i.message).join('\n')
        : 'Workflow YAML is invalid.'
    )
  }
  const doc = validation.doc
  const steps: WorkflowStep[] = doc.workflow.map((step) => compileStep(step, ctx))

  const compiled: CompiledWorkflow = { steps }

  if (doc.source) {
    compiled.artifactsRepo = {
      url: doc.source.repoUrl,
      baseBranch: doc.source.sourceBranch ?? 'main',
    }
    if (doc.source.targetBranch) {
      compiled.defaultTargetBranch = doc.source.targetBranch
    }
  }

  const orchestratorAliases = new Set(
    doc.workflow.filter((s) => s.taskType === 'ORCHESTRATOR').map((s) => s.agentProfileCode)
  )
  if (orchestratorAliases.size > 0) {
    const bindings: Record<string, string> = {}
    for (const alias of orchestratorAliases) {
      bindings[alias] = ctx.profiles[alias]!
    }
    compiled.bindings = bindings
  }

  return compiled
}

function compileStep(step: WorkflowYamlStep, ctx: WorkflowYamlContext): WorkflowStep {
  const agentProfileId = ctx.profiles[step.agentProfileCode]!
  if (step.taskType === 'ORCHESTRATOR') {
    return {
      id: step.id,
      name: step.name,
      agentProfileId,
      taskType: 'ORCHESTRATOR',
      dependsOn: step.dependsOn,
      retryPolicy: step.retryPolicy,
      // The alias rides inside the orchestration map (engine validator
      // requires one identical agentProfileCode across all steps).
      orchestration: { ...step.orchestration, agentProfileCode: step.agentProfileCode },
    }
  }
  // INFERENCE (default): optional fields omitted when absent.
  const compiled: WorkflowStep = {
    id: step.id,
    name: step.name,
    agentProfileId,
    taskType: 'INFERENCE',
    dependsOn: step.dependsOn,
    pauseMode: step.pauseMode,
  }
  if (step.prompt !== undefined) compiled.prompt = step.prompt
  if (step.transitions !== undefined) compiled.transitions = step.transitions
  if (step.timeoutSeconds !== undefined) compiled.timeoutSeconds = step.timeoutSeconds
  if (step.maxRetries !== undefined) compiled.maxRetries = step.maxRetries
  return compiled
}

/**
 * The inverse: serialize a workflow (engine step JSON) back to the UI
 * YAML variant. Used on editor load and for export. Unknown INFERENCE
 * profile UUIDs (not in ctx) throw — the ctx must cover the project's
 * profiles.
 */
export function workflowToYaml(input: {
  name: string
  steps: WorkflowStep[]
  artifactsRepo?: ArtifactsRepo | null
  bindings?: Record<string, string> | null
  defaultTargetBranch?: string
  /** code → UUID for every project-accessible profile (the authoring
   * context); ORCHESTRATOR aliases come from the orchestration map, and
   * this map resolves INFERENCE steps' UUIDs back to codes. */
  profiles?: Record<string, string>
}): string {
  const uuidToCode: Record<string, string> = {}
  for (const [code, uuid] of Object.entries(input.profiles ?? input.bindings ?? {})) {
    uuidToCode[uuid] = code
  }

  const doc: Record<string, unknown> = {
    version: '1.0',
    id: input.name.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'workflow',
    name: input.name,
  }

  // models: the union of referenced codes (modelId unknown → code only).
  const modelCodes = new Set<string>()
  for (const step of input.steps) {
    if (step.taskType !== 'ORCHESTRATOR') continue
    const orch = (step.orchestration ?? {}) as Record<string, unknown>
    if (typeof orch.modelCode === 'string') modelCodes.add(orch.modelCode)
    const workers = orch.workers
    if (Array.isArray(workers)) {
      for (const w of workers) {
        const mc = (w as Record<string, unknown>).modelCode
        if (typeof mc === 'string') modelCodes.add(mc)
      }
    }
  }
  if (modelCodes.size > 0) {
    doc.models = [...modelCodes].sort().map((code) => ({ code }))
  }

  // source: from artifactsRepo + defaultTargetBranch.
  if (input.artifactsRepo?.url) {
    const source: Record<string, string> = { repoUrl: input.artifactsRepo.url }
    if (input.artifactsRepo.baseBranch) source.sourceBranch = input.artifactsRepo.baseBranch
    if (input.defaultTargetBranch) source.targetBranch = input.defaultTargetBranch
    doc.source = source
  }

  const workflow = input.steps.map((step) => {
    if (step.taskType === 'ORCHESTRATOR') {
      const orch = (step.orchestration ?? {}) as Record<string, unknown>
      const alias =
        typeof orch.agentProfileCode === 'string'
          ? orch.agentProfileCode
          : uuidToCode[step.agentProfileId] ?? step.agentProfileId
      const { agentProfileCode: _dropped, ...orchRest } = orch
      void _dropped
      return {
        id: step.id,
        name: step.name,
        taskType: 'ORCHESTRATOR' as const,
        agentProfileCode: alias,
        dependsOn: step.dependsOn ?? [],
        retryPolicy: step.retryPolicy,
        orchestration: orchRest,
      }
    }
    const code = uuidToCode[step.agentProfileId] ?? step.agentProfileId
    const yamlStep: Record<string, unknown> = {
      id: step.id,
      name: step.name,
      agentProfileCode: code,
      dependsOn: step.dependsOn ?? [],
    }
    // Engine JSON may carry explicit nulls for unset fields — the UI
    // YAML treats them as absent (the schema's fields are optional(),
    // not nullable).
    if (step.prompt != null) yamlStep.prompt = step.prompt
    if (step.transitions != null && Object.keys(step.transitions).length > 0)
      yamlStep.transitions = step.transitions
    if (step.timeoutSeconds != null) yamlStep.timeoutSeconds = step.timeoutSeconds
    if (step.maxRetries != null) yamlStep.maxRetries = step.maxRetries
    yamlStep.pauseMode = step.pauseMode ?? 'NONE'
    return yamlStep
  })

  doc.workflow = workflow
  return YAML.stringify(doc as unknown as WorkflowYamlDoc, { lineWidth: 0 })
}