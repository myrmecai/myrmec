// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  modelsApi,
  providersApi,
  type Model,
  type CreateModelRequest,
  type UpdateModelRequest,
  type ModelProviderConfig,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ContentAreaLayout } from '@/components/content-area-layout'
import {
  Plus,
  Pencil,
  Trash2,
  CheckCircle,
  XCircle,
  AlertCircle,
  Loader2,
  Zap,
  Heart,
} from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'

export function ModelsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editModel, setEditModel] = useState<Model | null>(null)
  const [testingModel, setTestingModel] = useState<string | null>(null)

  const { data: models, isLoading, error } = useQuery({
    queryKey: ['models'],
    queryFn: modelsApi.list,
  })

  const { data: providers = [] } = useQuery({
    queryKey: ['providers-admin'],
    queryFn: providersApi.listAll,
  })

  const createMutation = useMutation({
    mutationFn: modelsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['models'] })
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ code, data }: { code: string; data: UpdateModelRequest }) =>
      modelsApi.update(code, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['models'] })
      setEditModel(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: modelsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['models'] })
    },
  })

  const testMutation = useMutation({
    mutationFn: modelsApi.test,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['models'] })
      setTestingModel(null)
    },
    onError: () => {
      setTestingModel(null)
    },
  })

  const handleTest = (code: string) => {
    setTestingModel(code)
    testMutation.mutate(code)
  }

  const columns: ColumnDef<Model>[] = useMemo(() => [
    {
      accessorKey: 'code',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Code" />,
      cell: ({ row }) => <span className="font-mono text-sm">{row.original.code}</span>,
    },
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => <span className="font-medium">{row.original.name}</span>,
    },
    {
      accessorKey: 'providerName',
      header: 'Provider',
      cell: ({ row }) => <span className="text-sm">{row.original.providerName}</span>,
    },
    {
      accessorKey: 'inputPrice',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Input $/1M" />,
      cell: ({ row }) => (
        <span className="text-sm tabular-nums">
          {row.original.inputPrice != null
            ? row.original.currency === 'USD'
              ? `$${row.original.inputPrice.toFixed(2)}`
              : `${row.original.inputPrice.toFixed(2)} ${row.original.currency}`
            : '—'}
        </span>
      ),
    },
    {
      accessorKey: 'outputPrice',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Output $/1M" />,
      cell: ({ row }) => (
        <span className="text-sm tabular-nums">
          {row.original.outputPrice != null
            ? row.original.currency === 'USD'
              ? `$${row.original.outputPrice.toFixed(2)}`
              : `${row.original.outputPrice.toFixed(2)} ${row.original.currency}`
            : '—'}
        </span>
      ),
    },
    {
      accessorKey: 'modelId',
      header: 'Model ID',
      cell: ({ row }) => (
        <span className="font-mono text-sm max-w-[150px] truncate block">{row.original.modelId}</span>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <StatusBadge status={row.original.status} />
          <HealthBadge health={row.original.healthStatus} />
          {row.original.lastTestStatus === 'SUCCESS' && (
            <span title="Last test passed">
              <Zap className="h-4 w-4 text-yellow-500" />
            </span>
          )}
        </div>
      ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const model = row.original
        return (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => handleTest(model.code)}
              disabled={testingModel === model.code}
              title="Test connection"
            >
              {testingModel === model.code ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Zap className="h-4 w-4" />
              )}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditModel(model)}
              title="Edit"
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Model',
                  message: 'Are you sure you want to delete this model? This cannot be undone.',
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(model.code)
              }}
              title="Delete"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        )
      },
    },
  ], [testingModel, handleTest, deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading models...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load models</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">Models</h1>
          <p className="text-muted-foreground">Manage AI model configurations</p>
        </div>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="h-4 w-4 mr-2" />
              New Model
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <CreateModelForm
              providers={providers}
              onSubmit={(data) => createMutation.mutate(data)}
              isLoading={createMutation.isPending}
              error={createMutation.error?.message}
            />
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All Models</CardTitle>
          <CardDescription>{models?.length || 0} models configured</CardDescription>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={models ?? []}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {/* Edit Model Dialog */}
      <Dialog open={!!editModel} onOpenChange={(open) => !open && setEditModel(null)}>
        <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
          {editModel && (
            <EditModelForm
              model={editModel}
              onSubmit={(data) => updateMutation.mutate({ code: editModel.code, data })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

function StatusBadge({ status }: { status: 'ACTIVE' | 'INACTIVE' }) {
  if (status === 'ACTIVE') {
    return (
      <span className="inline-flex items-center gap-1 text-green-600 text-sm">
        <CheckCircle className="h-4 w-4" />
        Active
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1 text-muted-foreground text-sm">
      <XCircle className="h-4 w-4" />
      Inactive
    </span>
  )
}

function HealthBadge({ health }: { health: string }) {
  switch (health) {
    case 'HEALTHY':
      return <span title="Healthy"><Heart className="h-4 w-4 text-green-500" /></span>
    case 'DEGRADED':
      return <span title="Degraded"><AlertCircle className="h-4 w-4 text-yellow-500" /></span>
    case 'UNHEALTHY':
      return <span title="Unhealthy"><XCircle className="h-4 w-4 text-red-500" /></span>
    case 'LOADING':
      return <span title="Loading"><Loader2 className="h-4 w-4 text-blue-500 animate-spin" /></span>
    default:
      return null
  }
}

interface CreateModelFormProps {
  providers: ModelProviderConfig[]
  onSubmit: (data: CreateModelRequest) => void
  isLoading: boolean
  error?: string
}

function CreateModelForm({ providers, onSubmit, isLoading, error }: CreateModelFormProps) {
  const [provider, setProvider] = useState<string>('')

  // Set default provider when providers load
  const defaultProvider = providers.length > 0 ? providers[0].code : ''
  if (!provider && defaultProvider) {
    setProvider(defaultProvider)
  }

  // Get selected provider config
  const selectedProvider = providers.find((p) => p.code === provider)

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)

    const data: CreateModelRequest = {
      code: formData.get('code') as string,
      name: formData.get('name') as string,
      provider,
      modelId: formData.get('modelId') as string,
      apiEndpoint: (formData.get('apiEndpoint') as string) || undefined,
      supportsVision: formData.get('supportsVision') === 'on',
      inputPrice: parseFloat(formData.get('inputPrice') as string) || null,
      outputPrice: parseFloat(formData.get('outputPrice') as string) || null,
      currency: (formData.get('currency') as string) || 'USD',
    }

    onSubmit(data)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Model</DialogTitle>
        <DialogDescription>Configure a new AI model connection</DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 py-4">
        {/* Code */}
        <div className="space-y-2">
          <Label htmlFor="code">Model Code<RequiredMark /></Label>
          <Input
            id="code"
            name="code"
            placeholder="gpt4-turbo"
            pattern="^[a-zA-Z0-9_-]+$"
            required
          />
          <p className="text-xs text-muted-foreground">
            Unique identifier (letters, numbers, hyphens, underscores)
          </p>
        </div>

        {/* Name */}
        <div className="space-y-2">
          <Label htmlFor="name">Display Name<RequiredMark /></Label>
          <Input id="name" name="name" placeholder="GPT-4 Turbo" required />
        </div>

        {/* Provider */}
        <div className="space-y-2">
          <Label htmlFor="provider">Provider<RequiredMark /></Label>
          <select
            id="provider"
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {providers.map((p) => (
              <option key={p.code} value={p.code}>
                {p.name}
              </option>
            ))}
          </select>
          {selectedProvider?.description && (
            <p className="text-xs text-muted-foreground">{selectedProvider.description}</p>
          )}
        </div>

        {/* Model ID */}
        <div className="space-y-2">
          <Label htmlFor="modelId">Model ID<RequiredMark /></Label>
          <Input
            id="modelId"
            name="modelId"
            placeholder="gpt-4-turbo"
            required
          />
          <p className="text-xs text-muted-foreground">
            Provider-specific model identifier
          </p>
        </div>

        {/* API Endpoint */}
        <div className="space-y-2">
          <Label htmlFor="apiEndpoint">
            API Endpoint {!selectedProvider?.baseUrl ? '*' : '(optional - uses provider default)'}
          </Label>
          <Input
            id="apiEndpoint"
            name="apiEndpoint"
            placeholder={selectedProvider?.baseUrl || 'https://api.example.com/v1'}
            required={!selectedProvider?.baseUrl}
          />
          {selectedProvider?.baseUrl && (
            <p className="text-xs text-muted-foreground">
              Default: {selectedProvider.baseUrl}
            </p>
          )}
        </div>

        {/* Vision / multimodal capability */}
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="supportsVision"
            name="supportsVision"
            className="w-4 h-4"
          />
          <Label htmlFor="supportsVision" className="text-sm font-normal cursor-pointer">
            Supports vision (image inputs)
          </Label>
        </div>

        {/* Pricing */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="inputPrice">Input Price / 1M Tokens</Label>
            <Input
              id="inputPrice"
              name="inputPrice"
              type="number"
              step="0.01"
              min="0"
              placeholder="5.00"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="outputPrice">Output Price / 1M Tokens</Label>
            <Input
              id="outputPrice"
              name="outputPrice"
              type="number"
              step="0.01"
              min="0"
              placeholder="15.00"
            />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="currency">Currency</Label>
          <Input
            id="currency"
            name="currency"
            maxLength={3}
            defaultValue="USD"
            placeholder="USD"
          />
          <p className="text-xs text-muted-foreground">3-letter currency code</p>
        </div>

        {error && <div className="text-sm text-destructive">{error}</div>}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? (
            <>
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              Creating...
            </>
          ) : (
            'Create Model'
          )}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditModelFormProps {
  model: Model
  onSubmit: (data: UpdateModelRequest) => void
  isLoading: boolean
  error?: string
}

function EditModelForm({ model, onSubmit, isLoading, error }: EditModelFormProps) {
  const [showAdvanced, setShowAdvanced] = useState(false)

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)

    const data: UpdateModelRequest = {
      name: formData.get('name') as string,
      apiEndpoint: (formData.get('apiEndpoint') as string) || undefined,
      supportsVision: formData.get('supportsVision') === 'on',
      status: formData.get('status') as 'ACTIVE' | 'INACTIVE',
      inputPrice: parseFloat(formData.get('inputPrice') as string) || null,
      outputPrice: parseFloat(formData.get('outputPrice') as string) || null,
      currency: (formData.get('currency') as string) || undefined,
    }

    const infraConfigStr = formData.get('infraConfig') as string
    if (infraConfigStr?.trim()) {
      try { (data as Record<string, unknown>).infraConfig = JSON.parse(infraConfigStr) } catch {}
    }
    const defaultParamsStr = formData.get('defaultParams') as string
    if (defaultParamsStr?.trim()) {
      try { (data as Record<string, unknown>).defaultParams = JSON.parse(defaultParamsStr) } catch {}
    }

    onSubmit(data)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Model</DialogTitle>
        <DialogDescription>
          Update model configuration for <span className="font-mono">{model.code}</span>
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 py-4">
        {/* Read-only info */}
        <div className="grid grid-cols-3 gap-4 p-4 bg-muted rounded-lg">
          <div>
            <Label className="text-xs text-muted-foreground">Code</Label>
            <p className="font-mono text-sm">{model.code}</p>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">Provider</Label>
            <p className="text-sm">{model.providerName}</p>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">Model ID</Label>
            <p className="font-mono text-sm">{model.modelId}</p>
          </div>
        </div>

        {/* Name */}
        <div className="space-y-2">
          <Label htmlFor="name">Display Name<RequiredMark /></Label>
          <Input id="name" name="name" defaultValue={model.name} required />
        </div>

        {/* API Endpoint */}
        <div className="space-y-2">
          <Label htmlFor="apiEndpoint">API Endpoint</Label>
          <Input
            id="apiEndpoint"
            name="apiEndpoint"
            defaultValue={model.apiEndpoint || ''}
          />
        </div>

        {/* Vision / multimodal capability */}
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="supportsVision"
            name="supportsVision"
            defaultChecked={model.supportsVision}
            className="w-4 h-4"
          />
          <Label htmlFor="supportsVision" className="text-sm font-normal cursor-pointer">
            Supports vision (image inputs)
          </Label>
        </div>

        {/* Pricing */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="inputPrice">Input Price / 1M Tokens</Label>
            <Input
              id="inputPrice"
              name="inputPrice"
              type="number"
              step="0.01"
              min="0"
              defaultValue={model.inputPrice ?? ''}
              placeholder="5.00"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="outputPrice">Output Price / 1M Tokens</Label>
            <Input
              id="outputPrice"
              name="outputPrice"
              type="number"
              step="0.01"
              min="0"
              defaultValue={model.outputPrice ?? ''}
              placeholder="15.00"
            />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="currency">Currency</Label>
          <Input
            id="currency"
            name="currency"
            maxLength={3}
            defaultValue={model.currency || ''}
            placeholder="USD"
          />
          <p className="text-xs text-muted-foreground">3-letter currency code</p>
        </div>

        {/* Status */}
        <div className="space-y-2">
          <Label htmlFor="status">Status</Label>
          <select
            id="status"
            name="status"
            defaultValue={model.status}
            className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </div>

        {/* Advanced Configuration */}
        <div className="space-y-2">
          <button
            type="button"
            onClick={() => setShowAdvanced(!showAdvanced)}
            className="flex items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground"
          >
            {showAdvanced ? '▾' : '▸'} Advanced Configuration
          </button>
          {showAdvanced && (
            <div className="grid gap-4 p-4 border rounded-lg">
              <div className="space-y-2">
                <Label htmlFor="infraConfig">Infra Config (JSON)</Label>
                <textarea
                  id="infraConfig"
                  name="infraConfig"
                  rows={4}
                  defaultValue={model.infraConfig ? JSON.stringify(model.infraConfig, null, 2) : ''}
                  placeholder='{"gpu": "A100", "replicas": 2}'
                  className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                <p className="text-xs text-muted-foreground">Optional infrastructure configuration JSON</p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="defaultParams">Default Params (JSON)</Label>
                <textarea
                  id="defaultParams"
                  name="defaultParams"
                  rows={4}
                  defaultValue={model.defaultParams ? JSON.stringify(model.defaultParams, null, 2) : ''}
                  placeholder='{"temperature": 0.7, "max_tokens": 4096}'
                  className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                <p className="text-xs text-muted-foreground">Optional default model parameters JSON</p>
              </div>
            </div>
          )}
        </div>

        {error && <div className="text-sm text-destructive">{error}</div>}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? (
            <>
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              Saving...
            </>
          ) : (
            'Save Changes'
          )}
        </Button>
      </DialogFooter>
    </form>
  )
}