// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  toolsApi,
  type Tool,
  type ToolType,
  type ToolStatus,
  type CreateToolRequest,
  type UpdateToolRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import { Textarea } from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  Pencil,
  Trash2,
  Wrench,
  AlertCircle,
  ExternalLink,
  Lock,
} from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

const toolTypeLabels: Record<ToolType, { label: string; color: string }> = {
  SYSTEM: { label: 'System', color: 'bg-blue-100 text-blue-800' },
  FILE: { label: 'File', color: 'bg-cyan-100 text-cyan-800' },
  INTEGRATION: { label: 'Integration', color: 'bg-purple-100 text-purple-800' },
  DATABASE: { label: 'Database', color: 'bg-orange-100 text-orange-800' },
  GIT: { label: 'Git', color: 'bg-indigo-100 text-indigo-800' },
  CUSTOM: { label: 'Custom', color: 'bg-gray-100 text-gray-800' },
}

const statusColors: Record<ToolStatus, string> = {
  ACTIVE: 'bg-green-600',
  DISABLED: 'bg-gray-500',
  DEPRECATED: 'bg-yellow-600',
}

export function ToolsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editTool, setEditTool] = useState<Tool | null>(null)

  const { data: tools, isLoading, error } = useQuery({
    queryKey: ['tools'],
    queryFn: toolsApi.list,
  })

  const createMutation = useMutation({
    mutationFn: toolsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tools'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ code, data }: { code: string; data: UpdateToolRequest }) =>
      toolsApi.update(code, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tools'] })
      setEditTool(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: toolsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tools'] })
    },
  })

  const columns: ColumnDef<Tool>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Tool" />,
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          {row.original.isSystem ? (
            <span title="System tool">
              <Lock className="h-4 w-4 text-muted-foreground" />
            </span>
          ) : (
            <Wrench className="h-4 w-4 text-muted-foreground" />
          )}
          <div>
            <div className="font-medium">{row.original.name}</div>
            <div className="text-xs text-muted-foreground font-mono">{row.original.code}</div>
            {row.original.description && (
              <div className="text-xs text-muted-foreground truncate max-w-[300px]">
                {row.original.description}
              </div>
            )}
          </div>
        </div>
      ),
    },
    {
      accessorKey: 'toolType',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Type" />,
      cell: ({ row }) => (
        <Badge variant="outline" className={toolTypeLabels[row.original.toolType].color}>
          {toolTypeLabels[row.original.toolType].label}
        </Badge>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) => (
        <Badge className={statusColors[row.original.status]}>
          {row.original.status}
        </Badge>
      ),
    },
    {
      id: 'docs',
      header: 'Docs',
      enableSorting: false,
      cell: ({ row }) =>
        row.original.docsUrl ? (
          <a
            href={row.original.docsUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="text-blue-600 hover:underline flex items-center gap-1 text-sm"
          >
            <ExternalLink className="h-3 w-3" />
            Docs
          </a>
        ) : (
          <span className="text-muted-foreground text-sm">-</span>
        ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const tool = row.original
        return (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditTool(tool)}
            >
              <Pencil className="h-4 w-4" />
            </Button>
            {!tool.isSystem && (
              <Button
                variant="ghost"
                size="icon"
                title="Delete"
                onClick={async () => {
                  const confirmed = await dialogService.showConfirmDialog({
                    title: 'Delete Tool',
                    message: 'Delete this tool? This cannot be undone.',
                    severity: 'error',
                    type: 'warning',
                    confirmLabel: 'Delete',
                    cancelLabel: 'Cancel',
                  })
                  if (confirmed) deleteMutation.mutate(tool.code)
                }}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            )}
          </div>
        )
      },
    },
  ], [deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading tools...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load tools</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Tools</h1>
          <p className="text-muted-foreground">
            Manage tools available for agent profiles
          </p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Tool
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <ToolForm
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Tools</CardTitle>
          <CardDescription>
            {tools?.length || 0} tools registered
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={tools ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Tool Dialog */}
      <Dialog open={!!editTool} onOpenChange={(open) => !open && setEditTool(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editTool && (
            <ToolEditForm
              tool={editTool}
              onSubmit={(data) => updateMutation.mutate({ code: editTool.code, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

interface ToolFormProps {
  onSubmit: (data: CreateToolRequest) => void
  isLoading: boolean
  error?: string
}

function ToolForm({ onSubmit, isLoading, error }: ToolFormProps) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [toolType, setToolType] = useState<ToolType>('CUSTOM')
  const [docsUrl, setDocsUrl] = useState('')
  const [configSchema, setConfigSchema] = useState('')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    let parsedSchema: Record<string, unknown> | undefined
    if (configSchema.trim()) {
      try {
        parsedSchema = JSON.parse(configSchema)
      } catch {
        alert('Invalid JSON in config schema')
        return
      }
    }
    onSubmit({
      code,
      name,
      description: description || undefined,
      toolType,
      configSchema: parsedSchema,
      docsUrl: docsUrl || undefined,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Tool</DialogTitle>
        <DialogDescription>
          Register a new tool for agent profiles to use.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md flex items-center gap-2">
            <AlertCircle className="h-4 w-4" />
            {error}
          </div>
        )}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="code">Code<RequiredMark /></Label>
            <Input
              id="code"
              value={code}
              onChange={(e) => setCode(e.target.value.toLowerCase())}
              placeholder="my-tool"
              pattern="^[a-z][a-z0-9_-]*$"
              required
            />
            <p className="text-xs text-muted-foreground">
              Lowercase letters, numbers, hyphens, underscores
            </p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="name">Name<RequiredMark /></Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="My Tool"
              required
            />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What this tool does..."
            rows={2}
          />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="toolType">Type<RequiredMark /></Label>
            <select
              id="toolType"
              value={toolType}
              onChange={(e) => setToolType(e.target.value as ToolType)}
              required
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option value="CUSTOM">Custom</option>
              <option value="SYSTEM">System</option>
              <option value="INTEGRATION">Integration</option>
              <option value="DATABASE">Database</option>
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="docsUrl">Docs URL</Label>
            <Input
              id="docsUrl"
              type="url"
              value={docsUrl}
              onChange={(e) => setDocsUrl(e.target.value)}
              placeholder="https://docs.example.com"
            />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="configSchema">Config Schema (JSON)</Label>
          <Textarea
            id="configSchema"
            value={configSchema}
            onChange={(e) => setConfigSchema(e.target.value)}
            placeholder='{"type": "object", "properties": {...}}'
            rows={4}
            className="font-mono text-sm"
          />
          <p className="text-xs text-muted-foreground">
            JSON Schema defining required configuration for this tool
          </p>
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Creating...' : 'Create Tool'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface ToolEditFormProps {
  tool: Tool
  onSubmit: (data: UpdateToolRequest) => void
  isLoading: boolean
  error?: string
}

function ToolEditForm({ tool, onSubmit, isLoading, error }: ToolEditFormProps) {
  const [name, setName] = useState(tool.name)
  const [description, setDescription] = useState(tool.description || '')
  const [toolType, setToolType] = useState<ToolType>(tool.toolType)
  const [docsUrl, setDocsUrl] = useState(tool.docsUrl || '')
  const [configSchema, setConfigSchema] = useState(
    tool.configSchema ? JSON.stringify(tool.configSchema, null, 2) : ''
  )
  const [status, setStatus] = useState<ToolStatus>(tool.status)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    let parsedSchema: Record<string, unknown> | undefined
    if (configSchema.trim()) {
      try {
        parsedSchema = JSON.parse(configSchema)
      } catch {
        alert('Invalid JSON in config schema')
        return
      }
    }
    onSubmit({
      name,
      description: description || undefined,
      toolType,
      configSchema: parsedSchema,
      docsUrl: docsUrl || undefined,
      status,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Tool: {tool.code}</DialogTitle>
        <DialogDescription>
          {tool.isSystem && (
            <span className="text-yellow-600 flex items-center gap-1">
              <Lock className="h-3 w-3" />
              System tool - some fields may be restricted
            </span>
          )}
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md flex items-center gap-2">
            <AlertCircle className="h-4 w-4" />
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
          />
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="toolType">Type</Label>
            <select
              id="toolType"
              value={toolType}
              onChange={(e) => setToolType(e.target.value as ToolType)}
              required
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option value="CUSTOM">Custom</option>
              <option value="SYSTEM">System</option>
              <option value="INTEGRATION">Integration</option>
              <option value="DATABASE">Database</option>
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="status">Status</Label>
            <select
              id="status"
              value={status}
              onChange={(e) => setStatus(e.target.value as ToolStatus)}
              required
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <option value="ACTIVE">Active</option>
              <option value="DISABLED">Disabled</option>
              <option value="DEPRECATED">Deprecated</option>
            </select>
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="docsUrl">Docs URL</Label>
          <Input
            id="docsUrl"
            type="url"
            value={docsUrl}
            onChange={(e) => setDocsUrl(e.target.value)}
            placeholder="https://docs.example.com"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="configSchema">Config Schema (JSON)</Label>
          <Textarea
            id="configSchema"
            value={configSchema}
            onChange={(e) => setConfigSchema(e.target.value)}
            rows={6}
            className="font-mono text-sm"
          />
        </div>
      </div>
      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Save Changes'}
        </Button>
      </DialogFooter>
    </form>
  )
}