// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { useMutation } from '@tanstack/react-query'
import { AlertCircle } from 'lucide-react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import { CanvasPreview } from '@/components/workflow/CanvasPreview'
import {
  workflowsApi,
  type Workflow,
  type AgentProfile,
  type Model,
} from '@/lib/api'
import {
  validateWorkflowYaml,
  type WorkflowYamlContext,
} from '@/lib/workflow-yaml-schema'
import {
  yamlToWorkflow,
  workflowToYaml,
  type CompiledWorkflow,
} from '@/lib/workflow-yaml-convert'

/**
 * The YAML authoring surface (spec
 * 2026-09-08-workflow-yaml-authoring-design §5): Monaco is the single
 * editing surface; the canvas is a read-only Preview; the issues panel
 * renders live tier-1 validation. Save compiles to the engine step
 * JSON and POSTs bindings when ORCHESTRATOR steps exist; Publish keeps
 * the engine validator gate (handled by the parent header).
 */
export interface WorkflowYamlEditorApi {
  save: () => void
  isSaveable: () => boolean
}

interface WorkflowYamlEditorProps {
  workflow: Workflow
  projectId: string
  profiles: AgentProfile[]
  models: Model[]
  readOnly?: boolean
  /** Dirty state flows up (header's "Unsaved changes" + gating). */
  onDirtyChange: (dirty: boolean) => void
  /** The latest successful compile flows up (Run-dialog target branch). */
  onCompiledChange?: (compiled: CompiledWorkflow | null) => void
  /** Imperative handle for the header's Save button (a plain mutable
   * ref object — React's RefObject.current is readonly in effects). */
  apiRef?: { current: WorkflowYamlEditorApi | null }
}

export function WorkflowYamlEditor({
  workflow,
  projectId,
  profiles,
  models,
  readOnly = false,
  onDirtyChange,
  onCompiledChange,
  apiRef,
}: WorkflowYamlEditorProps) {
  const ctx = useMemo<WorkflowYamlContext>(
    () => ({
      profiles: Object.fromEntries(profiles.map((p) => [p.name, p.id])),
      models: new Set(models.map((m) => m.code)),
    }),
    [profiles, models]
  )

  const initialYaml = useMemo(
    () =>
      workflowToYaml({
        name: workflow.name,
        steps: workflow.steps,
        artifactsRepo: workflow.artifactsRepo,
        bindings: workflow.orchestrationBindings ?? null,
        profiles: Object.fromEntries(profiles.map((p) => [p.name, p.id])),
      }),
    // Recompute only when a save bumps the version — never on refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workflow.id, workflow.version]
  )

  const [yamlText, setYamlText] = useState(initialYaml)
  const [saveError, setSaveError] = useState<string | null>(null)
  const onDirtyRef = useRef(onDirtyChange)
  onDirtyRef.current = onDirtyChange
  const onCompiledRef = useRef(onCompiledChange)
  onCompiledRef.current = onCompiledChange

  useEffect(() => {
    setYamlText(initialYaml)
  }, [initialYaml])

  const validation = useMemo(() => validateWorkflowYaml(yamlText, ctx), [yamlText, ctx])

  // The compile for Preview + Run-dialog defaults; null while invalid.
  const compiled = useMemo<CompiledWorkflow | null>(() => {
    if (!validation.ok) return null
    try {
      return yamlToWorkflow(yamlText, ctx)
    } catch {
      return null
    }
  }, [validation.ok, yamlText, ctx])

  useEffect(() => {
    onCompiledRef.current?.(compiled)
  }, [compiled])

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!compiled) throw new Error('Fix validation issues before saving.')
      await workflowsApi.update(projectId, workflow.id, {
        name: workflow.name,
        description: workflow.description || undefined,
        steps: compiled.steps,
        inputSchema: workflow.inputSchema ?? undefined,
        artifactsRepo: compiled.artifactsRepo,
        status: workflow.status,
      })
      if (compiled.bindings) {
        await workflowsApi.setOrchestrationBindings(projectId, workflow.id, compiled.bindings)
      }
    },
    onSuccess: () => {
      setSaveError(null)
      onDirtyRef.current(false)
    },
    onError: (err: unknown) => {
      setSaveError(err instanceof Error ? err.message : 'Failed to save workflow')
    },
  })

  const save = useCallback(() => {
    if (!validation.ok || saveMutation.isPending) return
    saveMutation.mutate()
  }, [validation.ok, saveMutation])

  // Expose the imperative handle to the parent header. A fresh object
  // each render keeps the latest save/validation closure; the parent's
  // ref object is a plain mutable { current } — no ref-type friction.
  useEffect(() => {
    if (apiRef) {
      apiRef.current = { save, isSaveable: () => validation.ok }
    }
    return () => {
      if (apiRef) apiRef.current = null
    }
  }, [apiRef, save, validation.ok])

  const handleChange = useCallback((value: string | undefined) => {
    setYamlText(value ?? '')
    onDirtyRef.current(true)
  }, [])

  const grouped = useMemo(() => {
    const byCode = new Map<string, typeof validation.issues>()
    for (const issue of validation.issues) {
      const list = byCode.get(issue.code) ?? []
      list.push(issue)
      byCode.set(issue.code, list)
    }
    return byCode
  }, [validation.issues])

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <Tabs defaultValue="editor" className="flex-1 flex flex-col overflow-hidden">
        <div className="border-b px-3 py-2 flex items-center justify-between bg-card">
          <TabsList>
            <TabsTrigger value="editor">Editor</TabsTrigger>
            <TabsTrigger value="preview" disabled={!compiled}>
              Preview
            </TabsTrigger>
          </TabsList>
          {!validation.ok && (
            <Badge variant="destructive" className="gap-1">
              <AlertCircle className="h-3 w-3" />
              {validation.issues.length} issue{validation.issues.length === 1 ? '' : 's'}
            </Badge>
          )}
          {readOnly && <span className="text-xs text-muted-foreground">Read-only (archived)</span>}
        </div>

        <TabsContent value="editor" className="flex-1 overflow-hidden mt-0">
          <Editor
            value={yamlText}
            onChange={readOnly ? undefined : handleChange}
            language="yaml"
            theme="vs-light"
            height="100%"
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              automaticLayout: true,
              tabSize: 2,
              readOnly,
            }}
          />
        </TabsContent>

        <TabsContent value="preview" className="flex-1 overflow-hidden mt-0">
          {compiled && (
            <CanvasPreview
              steps={compiled.steps}
              profiles={profiles.map((p) => ({ id: p.id, name: p.name }))}
            />
          )}
        </TabsContent>
      </Tabs>

      {/* Issues panel */}
      <div className="border-t max-h-52 overflow-y-auto bg-card/50">
        {saveError && (
          <p className="text-sm text-red-600 px-3 py-2 border-b">
            <AlertCircle className="h-3.5 w-3.5 inline mr-1" />
            {saveError}
          </p>
        )}
        {validation.issues.length === 0 && !saveError ? (
          <p className="text-xs text-muted-foreground px-3 py-2">
            YAML is valid. Save &amp; Publish from the header.
          </p>
        ) : (
          <ul className="text-sm">
            {[...grouped.entries()].map(([code, issues]) => (
              <li key={code}>
                <p className="text-xs font-semibold text-muted-foreground px-3 pt-2 pb-1 uppercase tracking-wide">
                  {code === 'YAML_PARSE'
                    ? 'YAML parse'
                    : code === 'SCHEMA'
                      ? 'Schema'
                      : 'Workflow rules'}
                </p>
                <ul>
                  {issues.map((issue, i) => (
                    <li
                      key={i}
                      className="text-red-600 px-3 py-1 border-t border-dashed text-xs whitespace-pre-wrap"
                    >
                      {issue.line ? `Line ${issue.line}: ` : ''}
                      {issue.message}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}