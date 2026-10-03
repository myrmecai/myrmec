// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import * as monaco from 'monaco-editor'
import { loader } from '@monaco-editor/react'
import { configureMonacoYaml, type JSONSchema, type MonacoYaml } from 'monaco-yaml'
import { zodToJsonSchema } from 'zod-to-json-schema'
import { uiWorkflowYamlDocSchema } from '@/lib/workflow-yaml-schema'
// Vite worker shim (monaco-yaml README "Why doesn't it work with Vite?"):
// the editor + yaml language workers must be constructed through
// MonacoEnvironment.getWorker so Vite bundles them with ?worker URLs.
// NOTE: monaco-editor is PINNED to ~0.54.0 in package.json: 0.54 ships
// the legacy-descriptor compat shim for createWebWorker that
// monaco-worker-manager 2.0.1 (monaco-yaml's worker layer) depends on;
// 0.55 removed it ("custom AMD workers don't work anymore") and every
// YAML language request falls back to a handler-less main-thread worker.
import YamlWorker from './yaml.worker.js?worker'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

/**
 * The workflow YAML editing surface is a JSON Schema driven Monaco YAML
 * editor (the 2026-09-08 workflow-yaml-authoring spec's behavior,
 * implemented here; the spec doc was deleted in the 2026-10-03 docs
 * cleanup): the schema is
 * derived from the canonical Zod UI schema (workflow-yaml-schema.ts) via
 * zod-to-json-schema, then bound to the same monaco instance the
 * @monaco-editor/react wrapper loads through `loader`.
 *
 * The agent profile and model enum values are injected at call time so
 * completion only offers resolvable codes. The static schema still
 * validates when the lists arrive empty (the cross-step tier 1 checks in
 * validateWorkflowYaml remain the authoritative save gate).
 *
 * loader.config must run at module import time: the <Editor> child calls
 * loader.init() in its own effect, which runs before this component's
 * effects, so configuring inside an effect would be too late.
 */
loader.config({ monaco })

// Debug handle (dev ergonomics + headless verification): the monaco instance
// is module-scoped under Vite, unreachable from the console otherwise.
if (import.meta.env.DEV) {
  ;(globalThis as Record<string, unknown>).__myrmecMonaco = monaco
}

// Worker wiring must also run at import time (before the first editor
// mount) or Monaco falls back to main-thread workers, which is exactly
// the failure mode this shim prevents.
globalThis.MonacoEnvironment = {
  getWorker(_moduleId: string, label: string): Worker {
    if (label === 'yaml') return new YamlWorker()
    return new EditorWorker()
  },
}

const SCHEMA_URI = 'myrmec://schemas/workflow-yaml.json'

let lastInputs: string | null = null
let registration: MonacoYaml | null = null

/** Deep clone via structuredClone when available, JSON fallback. */
function deepClone<T>(value: T): T {
  if (typeof structuredClone === 'function') return structuredClone(value)
  return JSON.parse(JSON.stringify(value)) as T
}

/** Replace the enum at node in place when node is a plain object. */
function injectEnum(node: unknown, values: string[]): void {
  if (node && typeof node === 'object' && !Array.isArray(node)) {
    const schema = node as { enum?: unknown[] }
    schema.enum = [...values]
  }
}

/**
 * Walk the converted schema and swap the static enums for the live
 * values. Paths follow the inlined ($refStrategy: 'none') shape; missing
 * paths are skipped silently so the static schema still applies.
 */
function injectLiveEnums(
  root: Record<string, unknown>,
  profiles: { name: string }[],
  models: { code: string }[]
): void {
  const profileNames = profiles.map((p) => p.name)
  const modelCodes = models.map((m) => m.code)
  if (profileNames.length === 0 && modelCodes.length === 0) return

  const props = root.properties as Record<string, unknown> | undefined
  const steps = props?.workflow
  const stepItems =
    steps && typeof steps === 'object' && !Array.isArray(steps)
      ? (steps as { items?: unknown }).items
      : undefined
  const anyOf =
    stepItems && typeof stepItems === 'object' && !Array.isArray(stepItems)
      ? (stepItems as { anyOf?: unknown[] }).anyOf
      : undefined
  if (!Array.isArray(anyOf)) return

  for (const step of anyOf) {
    if (!step || typeof step !== 'object' || Array.isArray(step)) continue
    const props = (step as { properties?: Record<string, unknown> }).properties
    if (!props) continue
    if (profileNames.length > 0) injectEnum(props.agentProfileCode, profileNames)
    const orchestration = props.orchestration
    if (!orchestration || typeof orchestration !== 'object' || Array.isArray(orchestration)) {
      continue
    }
    const orchProps = (orchestration as { properties?: Record<string, unknown> }).properties
    if (!orchProps) continue
    if (modelCodes.length > 0) injectEnum(orchProps.modelCode, modelCodes)
    const helpers = orchProps.helpers
    const helperItems =
      helpers && typeof helpers === 'object' && !Array.isArray(helpers)
        ? (helpers as { items?: unknown }).items
        : undefined
    const helperProps =
      helperItems && typeof helperItems === 'object' && !Array.isArray(helperItems)
        ? (helperItems as { properties?: Record<string, unknown> }).properties
        : undefined
    if (helperProps && modelCodes.length > 0) injectEnum(helperProps.modelCode, modelCodes)
  }
}

/**
 * Bind monaco-yaml to the monaco instance @monaco-editor/react loads
 * and register the workflow YAML schema. Reconfigures only when the
 * profile/model inputs change; identical inputs are a no-op.
 */
export function setupWorkflowYamlEditor(
  profiles: { name: string }[],
  models: { code: string }[]
): void {
  const serialized = JSON.stringify({
    profiles: profiles.map((p) => p.name),
    models: models.map((m) => m.code),
  })
  if (serialized === lastInputs) return
  lastInputs = serialized

  // configureMonacoYaml does not dispose the previous registration; drop
  // it first so provider registrations do not stack across reconfigures.
  registration?.dispose()

  // zod-to-json-schema's JsonSchema7Type is structurally compatible with
  // monaco-yaml's JSONSchema but not nominally; cast through unknown.
  const schema = deepClone(
    zodToJsonSchema(uiWorkflowYamlDocSchema, { $refStrategy: 'none', target: 'jsonSchema7' })
  ) as unknown as JSONSchema
  injectLiveEnums(schema as unknown as Record<string, unknown>, profiles, models)

  registration = configureMonacoYaml(monaco, {
    enableSchemaRequest: false,
    schemas: [{ uri: SCHEMA_URI, fileMatch: ['*'], schema }],
  })
}