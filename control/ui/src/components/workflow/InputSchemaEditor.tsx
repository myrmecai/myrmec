import { useMemo, useState } from 'react'
import Editor from '@monaco-editor/react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Plus, Trash2, AlertCircle } from 'lucide-react'

export type InputFieldType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'enum'
  | 'object'
  | 'array<string>'

export interface InputFieldRow {
  name: string
  type: InputFieldType
  required: boolean
  description?: string
  default?: string
  enumValues?: string
}

interface InputSchemaEditorProps {
  value: Record<string, unknown> | null
  readOnly?: boolean
  onChange: (schema: Record<string, unknown> | null) => void
}

interface ParseResult {
  rows: InputFieldRow[]
  fitsBuilder: boolean
}

function parseSchema(schema: Record<string, unknown> | null): ParseResult {
  if (!schema) return { rows: [], fitsBuilder: true }
  if (schema.type !== 'object' || typeof schema.properties !== 'object') {
    return { rows: [], fitsBuilder: false }
  }
  const props = schema.properties as Record<string, unknown>
  const requiredList = Array.isArray(schema.required)
    ? (schema.required as string[])
    : []

  const rows: InputFieldRow[] = []
  let fitsBuilder = true

  // Reject unsupported top-level keys
  const knownKeys = new Set([
    'type',
    'properties',
    'required',
    '$schema',
    'title',
    'description',
  ])
  for (const key of Object.keys(schema)) {
    if (!knownKeys.has(key)) {
      fitsBuilder = false
      break
    }
  }

  if (fitsBuilder) {
    for (const [name, raw] of Object.entries(props)) {
      if (!raw || typeof raw !== 'object') {
        fitsBuilder = false
        break
      }
      const def = raw as Record<string, unknown>
      const row: InputFieldRow = {
        name,
        type: 'string',
        required: requiredList.includes(name),
        description:
          typeof def.description === 'string' ? def.description : undefined,
        default:
          def.default !== undefined ? JSON.stringify(def.default) : undefined,
      }

      if (Array.isArray(def.enum)) {
        row.type = 'enum'
        row.enumValues = (def.enum as unknown[]).join(', ')
      } else if (def.type === 'string') {
        row.type = 'string'
      } else if (def.type === 'number' || def.type === 'integer') {
        row.type = 'number'
      } else if (def.type === 'boolean') {
        row.type = 'boolean'
      } else if (
        def.type === 'array' &&
        typeof def.items === 'object' &&
        def.items !== null &&
        (def.items as Record<string, unknown>).type === 'string'
      ) {
        row.type = 'array<string>'
      } else if (def.type === 'object') {
        row.type = 'object'
      } else {
        fitsBuilder = false
        break
      }

      rows.push(row)
    }
  }

  return { rows, fitsBuilder }
}

function rowsToSchema(rows: InputFieldRow[]): Record<string, unknown> | null {
  if (rows.length === 0) return null
  const properties: Record<string, unknown> = {}
  const required: string[] = []

  for (const row of rows) {
    if (!row.name.trim()) continue
    const prop: Record<string, unknown> = {}

    switch (row.type) {
      case 'string':
        prop.type = 'string'
        break
      case 'number':
        prop.type = 'number'
        break
      case 'boolean':
        prop.type = 'boolean'
        break
      case 'enum': {
        const values = (row.enumValues ?? '')
          .split(',')
          .map((v) => v.trim())
          .filter((v) => v.length > 0)
        prop.type = 'string'
        prop.enum = values
        break
      }
      case 'object':
        prop.type = 'object'
        break
      case 'array<string>':
        prop.type = 'array'
        prop.items = { type: 'string' }
        break
    }

    if (row.description) prop.description = row.description
    if (row.default !== undefined && row.default !== '') {
      try {
        prop.default = JSON.parse(row.default)
      } catch {
        prop.default = row.default
      }
    }

    properties[row.name] = prop
    if (row.required) required.push(row.name)
  }

  const out: Record<string, unknown> = { type: 'object', properties }
  if (required.length > 0) out.required = required
  return out
}

export function InputSchemaEditor({
  value,
  readOnly = false,
  onChange,
}: InputSchemaEditorProps) {
  const parsed = useMemo(() => parseSchema(value), [value])
  const [advanced, setAdvanced] = useState(!parsed.fitsBuilder)
  const [rows, setRows] = useState<InputFieldRow[]>(parsed.rows)

  const updateRows = (next: InputFieldRow[]) => {
    setRows(next)
    onChange(rowsToSchema(next))
  }

  const addRow = () => {
    updateRows([
      ...rows,
      { name: `field_${rows.length + 1}`, type: 'string', required: false },
    ])
  }

  const removeRow = (idx: number) => {
    updateRows(rows.filter((_, i) => i !== idx))
  }

  const updateRow = (idx: number, patch: Partial<InputFieldRow>) => {
    updateRows(rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }

  if (advanced || !parsed.fitsBuilder) {
    return (
      <div className="space-y-2">
        {!parsed.fitsBuilder && (
          <div className="flex items-start gap-2 rounded-md border border-yellow-300 bg-yellow-50 p-2 text-xs text-yellow-800">
            <AlertCircle className="h-4 w-4 mt-0.5 shrink-0" />
            <span>
              Schema authored externally — using advanced JSON view. Clear and
              start over to use the field builder.
            </span>
          </div>
        )}
        <div className="flex items-center justify-between">
          <Label className="text-xs">JSON Schema</Label>
          {parsed.fitsBuilder && (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => setAdvanced(false)}
            >
              Field builder
            </Button>
          )}
        </div>
        <div className="border rounded-md overflow-hidden h-[220px]">
          <Editor
            height="100%"
            language="json"
            value={value ? JSON.stringify(value, null, 2) : ''}
            onChange={(v) => {
              if (!v || !v.trim()) {
                onChange(null)
                return
              }
              try {
                onChange(JSON.parse(v))
              } catch {
                // ignore until valid
              }
            }}
            options={{
              minimap: { enabled: false },
              fontSize: 12,
              lineNumbers: 'off',
              scrollBeyondLastLine: false,
              readOnly,
            }}
            theme="vs-light"
          />
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label className="text-xs">Input fields</Label>
        <div className="flex items-center gap-1">
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => setAdvanced(true)}
          >
            Advanced
          </Button>
          {!readOnly && (
            <Button type="button" size="sm" variant="outline" onClick={addRow}>
              <Plus className="h-3 w-3 mr-1" />
              Add
            </Button>
          )}
        </div>
      </div>

      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground py-4 text-center border rounded-md">
          No inputs defined. Add a field to require user input when running.
        </p>
      ) : (
        <div className="space-y-2">
          {rows.map((row, idx) => (
            <div key={idx} className="border rounded-md p-2 space-y-2">
              <div className="flex items-center gap-2">
                <Input
                  value={row.name}
                  onChange={(e) => updateRow(idx, { name: e.target.value })}
                  placeholder="field_name"
                  className="h-7 text-xs flex-1"
                  disabled={readOnly}
                />
                <Select
                  value={row.type}
                  onValueChange={(v) =>
                    updateRow(idx, { type: v as InputFieldType })
                  }
                  disabled={readOnly}
                >
                  <SelectTrigger className="h-7 text-xs w-[110px]">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="string">string</SelectItem>
                    <SelectItem value="number">number</SelectItem>
                    <SelectItem value="boolean">boolean</SelectItem>
                    <SelectItem value="enum">enum</SelectItem>
                    <SelectItem value="array<string>">array</SelectItem>
                    <SelectItem value="object">object</SelectItem>
                  </SelectContent>
                </Select>
                {!readOnly && (
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7"
                    onClick={() => removeRow(idx)}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                )}
              </div>

              {row.type === 'enum' && (
                <Input
                  value={row.enumValues ?? ''}
                  onChange={(e) =>
                    updateRow(idx, { enumValues: e.target.value })
                  }
                  placeholder="comma, separated, values"
                  className="h-7 text-xs"
                  disabled={readOnly}
                />
              )}

              <div className="flex items-center gap-2">
                <label className="flex items-center gap-1 text-xs">
                  <input
                    type="checkbox"
                    checked={row.required}
                    onChange={(e) =>
                      updateRow(idx, { required: e.target.checked })
                    }
                    disabled={readOnly}
                  />
                  Required
                </label>
                <Input
                  value={row.description ?? ''}
                  onChange={(e) =>
                    updateRow(idx, { description: e.target.value })
                  }
                  placeholder="Description"
                  className="h-7 text-xs flex-1"
                  disabled={readOnly}
                />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
