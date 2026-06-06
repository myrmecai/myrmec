import { useMemo, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import Editor from '@monaco-editor/react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Loader2, Play, AlertCircle } from 'lucide-react'
import { workflowsApi, type Workflow } from '@/lib/api'

interface RunWorkflowDialogProps {
  workflow: Workflow
  open: boolean
  onClose: () => void
}

interface SchemaField {
  name: string
  type: 'string' | 'number' | 'boolean' | 'object' | 'array<string>'
  enum?: string[]
  required: boolean
  description?: string
  default?: unknown
}

function extractFields(schema: Record<string, unknown> | null): SchemaField[] {
  if (!schema || schema.type !== 'object') return []
  const props = schema.properties
  if (!props || typeof props !== 'object') return []
  const required = Array.isArray(schema.required)
    ? (schema.required as string[])
    : []

  const fields: SchemaField[] = []
  for (const [name, raw] of Object.entries(props as Record<string, unknown>)) {
    if (!raw || typeof raw !== 'object') continue
    const def = raw as Record<string, unknown>
    let type: SchemaField['type'] = 'string'
    let enumValues: string[] | undefined

    if (Array.isArray(def.enum)) {
      type = 'string'
      enumValues = (def.enum as unknown[]).map((v) => String(v))
    } else if (def.type === 'number' || def.type === 'integer') {
      type = 'number'
    } else if (def.type === 'boolean') {
      type = 'boolean'
    } else if (
      def.type === 'array' &&
      typeof def.items === 'object' &&
      def.items !== null &&
      (def.items as Record<string, unknown>).type === 'string'
    ) {
      type = 'array<string>'
    } else if (def.type === 'object') {
      type = 'object'
    } else {
      type = 'string'
    }

    fields.push({
      name,
      type,
      enum: enumValues,
      required: required.includes(name),
      description:
        typeof def.description === 'string' ? def.description : undefined,
      default: def.default,
    })
  }
  return fields
}

function defaultValue(f: SchemaField): unknown {
  if (f.default !== undefined) return f.default
  switch (f.type) {
    case 'boolean':
      return false
    case 'number':
      return ''
    case 'array<string>':
      return ''
    case 'object':
      return '{}'
    default:
      return ''
  }
}

export function RunWorkflowDialog({
  workflow,
  open,
  onClose,
}: RunWorkflowDialogProps) {
  const navigate = useNavigate()
  const fields = useMemo(() => extractFields(workflow.inputSchema), [workflow.inputSchema])

  const [values, setValues] = useState<Record<string, unknown>>(() => {
    const init: Record<string, unknown> = {}
    for (const f of fields) init[f.name] = defaultValue(f)
    return init
  })
  const [errors, setErrors] = useState<Record<string, string>>({})

  const setValue = (name: string, v: unknown) => {
    setValues((s) => ({ ...s, [name]: v }))
    setErrors((e) => {
      const { [name]: _, ...rest } = e
      return rest
    })
  }

  const startMutation = useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      workflowsApi.startRequest(workflow.projectId, workflow.id, {
        workflowId: workflow.id,
        input,
      }),
    onSuccess: (req) => {
      onClose()
      navigate({
        to: '/workflows/$workflowId/requests/$requestId',
        params: { workflowId: workflow.id, requestId: req.id },
        search: { projectId: workflow.projectId },
      })
    },
  })

  const validateAndBuild = (): Record<string, unknown> | null => {
    const out: Record<string, unknown> = {}
    const errs: Record<string, string> = {}

    for (const f of fields) {
      const raw = values[f.name]
      const isEmpty = raw === '' || raw === undefined || raw === null

      if (f.required && isEmpty) {
        errs[f.name] = 'Required'
        continue
      }
      if (isEmpty) continue

      switch (f.type) {
        case 'number': {
          const n = Number(raw)
          if (Number.isNaN(n)) {
            errs[f.name] = 'Must be a number'
          } else {
            out[f.name] = n
          }
          break
        }
        case 'boolean':
          out[f.name] = Boolean(raw)
          break
        case 'array<string>':
          out[f.name] = String(raw)
            .split(',')
            .map((s) => s.trim())
            .filter((s) => s.length > 0)
          break
        case 'object': {
          try {
            out[f.name] = JSON.parse(String(raw))
          } catch {
            errs[f.name] = 'Invalid JSON'
          }
          break
        }
        default:
          out[f.name] = raw
      }
    }

    setErrors(errs)
    return Object.keys(errs).length === 0 ? out : null
  }

  const handleSubmit = () => {
    const input = validateAndBuild()
    if (input === null) return
    startMutation.mutate(input)
  }

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Run workflow</DialogTitle>
          <DialogDescription>
            {workflow.name} • v{workflow.version}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 max-h-[60vh] overflow-y-auto pr-1">
          {fields.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              This workflow takes no inputs.
            </p>
          ) : (
            fields.map((f) => (
              <div key={f.name} className="space-y-1">
                <Label className="text-xs">
                  {f.name}
                  {f.required && <span className="text-red-600 ml-1">*</span>}
                </Label>
                {f.description && (
                  <p className="text-[11px] text-muted-foreground">
                    {f.description}
                  </p>
                )}

                {f.type === 'boolean' ? (
                  <label className="flex items-center gap-2 text-xs">
                    <input
                      type="checkbox"
                      checked={Boolean(values[f.name])}
                      onChange={(e) => setValue(f.name, e.target.checked)}
                    />
                    Enabled
                  </label>
                ) : f.enum ? (
                  <Select
                    value={String(values[f.name] ?? '')}
                    onValueChange={(v) => setValue(f.name, v)}
                  >
                    <SelectTrigger className="h-8 text-xs">
                      <SelectValue placeholder="Select…" />
                    </SelectTrigger>
                    <SelectContent>
                      {f.enum.map((opt) => (
                        <SelectItem key={opt} value={opt}>
                          {opt}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : f.type === 'object' ? (
                  <div className="border rounded-md overflow-hidden h-[120px]">
                    <Editor
                      height="100%"
                      language="json"
                      value={String(values[f.name] ?? '{}')}
                      onChange={(v) => setValue(f.name, v ?? '')}
                      options={{
                        minimap: { enabled: false },
                        fontSize: 12,
                        lineNumbers: 'off',
                        scrollBeyondLastLine: false,
                      }}
                      theme="vs-light"
                    />
                  </div>
                ) : (
                  <Input
                    type={f.type === 'number' ? 'number' : 'text'}
                    value={String(values[f.name] ?? '')}
                    onChange={(e) => setValue(f.name, e.target.value)}
                    placeholder={
                      f.type === 'array<string>'
                        ? 'comma, separated'
                        : undefined
                    }
                    className="h-8 text-xs"
                    aria-invalid={!!errors[f.name]}
                  />
                )}

                {errors[f.name] && (
                  <p className="text-[11px] text-red-600 flex items-center gap-1">
                    <AlertCircle className="h-3 w-3" />
                    {errors[f.name]}
                  </p>
                )}
              </div>
            ))
          )}

          {startMutation.isError && (
            <p className="text-xs text-red-600">
              Failed to start workflow.
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={startMutation.isPending}>
            {startMutation.isPending ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Play className="h-4 w-4 mr-2" />
            )}
            Run
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
