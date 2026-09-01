// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  providersApi,
  type CreateModelProviderRequest,
  type DeploymentType,
  type ModelProviderConfig,
  type ModelStatus,
  type TestModelResponse,
  type UpdateModelProviderRequest,
} from '@/lib/api'
import { ConnectionConfigSelect } from '@/features/platform/connections/components/ConnectionConfigSelect'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Cloud, Loader2, Pencil, Plug, Plus, Server, Trash2 } from 'lucide-react'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'
import { EntityStatus, TestStatus } from '@/lib/domain-constants'

const DEPLOYMENT_TYPES: DeploymentType[] = ['CLOUD', 'ON_PREMISE']
const STATUSES: ModelStatus[] = ['ACTIVE', 'INACTIVE']

export function ProvidersList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<ModelProviderConfig | null>(null)
  const [testResult, setTestResult] = useState<{ status: string; message: string; latencyMs: number } | null>(null)

  const {
    data: providers,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['providers-admin'],
    queryFn: () => providersApi.listAll(),
  })

  // Sort: ACTIVE first, then alphabetically by name
  const sortedProviders = useMemo(() => {
    if (!providers) return []
    return [...providers].sort((a, b) => {
      if (a.status !== b.status) {
        return a.status === 'ACTIVE' ? -1 : 1
      }
      return a.name.localeCompare(b.name)
    })
  }, [providers])

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['providers-admin'] })

  const createMutation = useMutation({
    mutationFn: (data: CreateModelProviderRequest) => providersApi.create(data),
    onSuccess: () => {
      invalidate()
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({
      code,
      data,
    }: {
      code: string
      data: UpdateModelProviderRequest
    }) => providersApi.update(code, data),
    onSuccess: () => {
      invalidate()
      setEditing(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (code: string) => providersApi.delete(code),
    onSuccess: invalidate,
  })

  const testConnectionMutation = useMutation({
    mutationFn: (code: string) => providersApi.test(code),
    onSuccess: (data: TestModelResponse) => {
      setTestResult({ status: data.status, message: data.message, latencyMs: data.latencyMs })
    },
    onError: (error: Error) => {
      setTestResult({ status: TestStatus.FAILED, message: error.message, latencyMs: 0 })
    },
  })

  const columns: ColumnDef<ModelProviderConfig>[] = useMemo(() => [
    {
      accessorKey: 'code',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Code" />,
      cell: ({ row }) => <span className="font-mono text-xs">{row.original.code}</span>,
    },
    {
      accessorKey: 'name',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Name" />,
      cell: ({ row }) => <span>{row.original.name}</span>,
    },
    {
      accessorKey: 'deploymentType',
      header: 'Deployment',
      cell: ({ row }) => (
        <div className="flex items-center gap-1.5 text-xs">
          {row.original.deploymentType === 'CLOUD' ? (
            <Cloud className="h-3.5 w-3.5" />
          ) : (
            <Server className="h-3.5 w-3.5" />
          )}
          {row.original.deploymentType}
        </div>
      ),
    },
    {
      accessorKey: 'connectionConfigId',
      header: 'Connection Config',
      enableSorting: false,
      cell: ({ row }) => {
        const id = row.original.connectionConfigId
        if (!id) return <span className="text-xs text-muted-foreground">—</span>
        const name = row.original.connectionConfigName
        return (
          <span className="text-xs font-mono" title={id}>
            {name ?? id}
          </span>
        )
      },
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => <SortedColumnHeader table={table} column={column} title="Status" />,
      cell: ({ row }) =>
        row.original.status === EntityStatus.ACTIVE ? (
          <span className="text-xs px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-700">
            active
          </span>
        ) : (
          <span className="text-xs px-2 py-0.5 rounded bg-muted text-muted-foreground">
            inactive
          </span>
        ),
    },
    {
      accessorKey: 'isSystem',
      header: 'System',
      enableSorting: false,
      cell: ({ row }) =>
        row.original.isSystem ? (
          <span className="text-xs px-2 py-0.5 rounded bg-blue-500/10 text-blue-700">
            system
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">custom</span>
        ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const p = row.original
        return (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setEditing(p)}
              title="Edit"
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              disabled={testConnectionMutation.isPending || p.status !== EntityStatus.ACTIVE || !p.connectionConfigId}
              title={
                p.status !== EntityStatus.ACTIVE
                  ? 'Provider must be ACTIVE to test connection'
                  : !p.connectionConfigId
                    ? 'A connection config must be linked to test connection'
                    : 'Test connection'
              }
              onClick={async () => {
                setTestResult(null)
                testConnectionMutation.mutate(p.code)
              }}
            >
              {testConnectionMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Plug className="h-4 w-4" />
              )}
            </Button>
            <Button
              variant="ghost"
              size="icon"
              disabled={p.isSystem}
              title={
                p.isSystem
                  ? 'System providers cannot be deleted'
                  : 'Delete'
              }
              onClick={async () => {
                const confirmed = await dialogService.showConfirmDialog({
                  title: 'Delete Provider',
                  message: `Delete provider "${p.code}"? Only non-system providers with no attached models can be deleted.`,
                  severity: 'error',
                  type: 'warning',
                  confirmLabel: 'Delete',
                  cancelLabel: 'Cancel',
                })
                if (confirmed) deleteMutation.mutate(p.code)
              }}
            >
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </div>
        )
      },
    },
  ], [deleteMutation])

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading providers...</div>
      </div>
    )
  }
  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load providers</div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-2">
            <Server className="h-7 w-7" />
            Model Providers
          </h1>
          <p className="text-muted-foreground">
            Configure cloud and on-premise LLM providers. System providers
            (Liquibase-seeded) can be disabled but not deleted.
          </p>
        </div>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>All Providers</CardTitle>
            <CardDescription>
              {providers?.length ?? 0} provider(s) configured.
            </CardDescription>
          </div>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger asChild>
              <Button variant="outline">
                <Plus className="h-4 w-4 mr-2" />
                New Provider
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-xl">
              <CreateProviderForm
                onSubmit={(data) => createMutation.mutate(data)}
                isLoading={createMutation.isPending}
                error={createMutation.error?.message}
              />
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent>
          <DataTable2
            columns={columns}
            data={sortedProviders}
            pagination={true}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      {testResult && (
        <div
          className={`fixed bottom-4 right-4 p-4 rounded-lg shadow-lg border ${
            testResult.status === TestStatus.SUCCESS
              ? 'bg-emerald-50 border-emerald-200'
              : 'bg-destructive/5 border-destructive/20'
          }`}
        >
          <div className="flex items-center gap-2">
            <span
              className={`font-semibold ${
                testResult.status === TestStatus.SUCCESS
                  ? 'text-emerald-700'
                  : 'text-destructive'
              }`}
            >
              {testResult.status === TestStatus.SUCCESS ? '✓' : '✗'} Test Connection
            </span>
            <span className="text-sm text-muted-foreground">
              {testResult.latencyMs}ms
            </span>
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            {testResult.message}
          </p>
          <Button
            variant="ghost"
            size="sm"
            className="mt-2 h-6 text-xs"
            onClick={() => setTestResult(null)}
          >
            Dismiss
          </Button>
        </div>
      )}

      <Dialog
        open={!!editing}
        onOpenChange={(open) => !open && setEditing(null)}
      >
        <DialogContent className="max-w-xl">
          {editing && (
            <EditProviderForm
              provider={editing}
              onSubmit={(data) =>
                updateMutation.mutate({ code: editing.code, data })
              }
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

interface CreateFormProps {
  onSubmit: (data: CreateModelProviderRequest) => void
  isLoading: boolean
  error?: string
}

function CreateProviderForm({ onSubmit, isLoading, error }: CreateFormProps) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [deploymentType, setDeploymentType] = useState<DeploymentType>('CLOUD')
  const [requiresAuth, setRequiresAuth] = useState(true)
  const [connectionConfigId, setConnectionConfigId] = useState<string | null>(null)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const payload: CreateModelProviderRequest = {
      code: code.trim(),
      name: name.trim(),
      baseUrl: baseUrl.trim() || null,
      deploymentType,
      requiresAuth,
      connectionConfigId: connectionConfigId || null,
    }
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Provider</DialogTitle>
        <DialogDescription>
          Register a new LLM provider. The code is immutable; everything
          else can be edited later.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
            {error}
          </div>
        )}

        <div className="space-y-2">
          <Label htmlFor="provider-code">Code<RequiredMark /></Label>
          <Input
            id="provider-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="my-internal-llm"
            required
            pattern="[a-z0-9_-]+"
          />
          <p className="text-xs text-muted-foreground">
            Lowercase letters, digits, dash, underscore. Immutable.
          </p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="provider-name">Name<RequiredMark /></Label>
          <Input
            id="provider-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Internal LLM"
            required
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="provider-base-url">Base URL</Label>
          <Input
            id="provider-base-url"
            type="url"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="https://api.example.com/v1"
          />
          <p className="text-xs text-muted-foreground">
            Default endpoint for models that don't specify their own. Optional for on-premise providers.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-2">
            <Label htmlFor="provider-deployment">Deployment</Label>
            <Select
              value={deploymentType}
              onValueChange={(v) => setDeploymentType(v as DeploymentType)}
            >
              <SelectTrigger id="provider-deployment">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DEPLOYMENT_TYPES.map((d) => (
                  <SelectItem key={d} value={d}>
                    {d}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2 flex flex-col">
            <Label>Requires authentication</Label>
            <div className="flex items-center gap-2 mt-2">
              <input
                id="provider-requires-auth"
                type="checkbox"
                checked={requiresAuth}
                onChange={(e) => setRequiresAuth(e.target.checked)}
              />
              <Label
                htmlFor="provider-requires-auth"
                className="cursor-pointer"
              >
                Yes (link a connection config)
              </Label>
            </div>
          </div>
        </div>

        {requiresAuth && (
        <div className="space-y-2">
          <Label htmlFor="provider-connection-config">Connection Config</Label>
          <ConnectionConfigSelect
            value={connectionConfigId}
            onChange={(v) => setConnectionConfigId(v)}
            filter={(c) => c.status === 'ACTIVE'}
            placeholder="Select a connection config…"
          />
          <p className="text-xs text-muted-foreground">
            Select a published connection config that holds the provider credential, or create one inline.
          </p>
        </div>
        )}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Creating...' : 'Create'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditFormProps {
  provider: ModelProviderConfig
  onSubmit: (data: UpdateModelProviderRequest) => void
  isLoading: boolean
  error?: string
}

function EditProviderForm({
  provider,
  onSubmit,
  isLoading,
  error,
}: EditFormProps) {
  const [name, setName] = useState(provider.name)
  const [baseUrl, setBaseUrl] = useState(provider.baseUrl ?? '')
  const [deploymentType, setDeploymentType] = useState<DeploymentType>(
    provider.deploymentType,
  )
  const [requiresAuth, setRequiresAuth] = useState(provider.requiresAuth)
  const [status, setStatus] = useState<ModelStatus>(provider.status)
  const [connectionConfigId, setConnectionConfigId] = useState<string | null>(
    provider.connectionConfigId ?? null,
  )

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      baseUrl: baseUrl.trim() || null,
      deploymentType,
      requiresAuth,
      status,
      connectionConfigId,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Provider</DialogTitle>
        <DialogDescription>
          <span className="font-mono text-xs">{provider.code}</span>
          {provider.isSystem && (
            <span className="ml-2 text-xs px-2 py-0.5 rounded bg-blue-500/10 text-blue-700">
              system
            </span>
          )}
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="edit-name">Name</Label>
          <Input
            id="edit-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-base-url">Base URL</Label>
          <Input
            id="edit-base-url"
            type="url"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="https://api.example.com/v1"
          />
          <p className="text-xs text-muted-foreground">
            Default endpoint for models that don't specify their own. Optional for on-premise providers.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-2">
            <Label htmlFor="edit-deployment">Deployment</Label>
            <Select
              value={deploymentType}
              onValueChange={(v) => setDeploymentType(v as DeploymentType)}
            >
              <SelectTrigger id="edit-deployment">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DEPLOYMENT_TYPES.map((d) => (
                  <SelectItem key={d} value={d}>
                    {d}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2 flex flex-col">
            <Label>Requires authentication</Label>
            <div className="flex items-center gap-2 mt-2">
              <input
                id="edit-requires-auth"
                type="checkbox"
                checked={requiresAuth}
                onChange={(e) => setRequiresAuth(e.target.checked)}
              />
              <Label
                htmlFor="edit-requires-auth"
                className="cursor-pointer"
              >
                Yes (link a connection config)
              </Label>
            </div>
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="edit-status">Status</Label>
          <Select
            value={status}
            onValueChange={(v) => setStatus(v as ModelStatus)}
          >
            <SelectTrigger id="edit-status">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {STATUSES.map((s) => (
                <SelectItem key={s} value={s}>
                  {s}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {requiresAuth && (
        <div className="space-y-2">
          <Label htmlFor="edit-connection-config">Connection Config</Label>
          <ConnectionConfigSelect
            value={connectionConfigId}
            onChange={(v) => setConnectionConfigId(v)}
            filter={(c) => c.status === 'ACTIVE'}
            placeholder="Select a connection config…"
          />
          <p className="text-xs text-muted-foreground">
            Select a published connection config that holds the provider credential, or create one inline.
          </p>
        </div>
        )}
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Save'}
        </Button>
      </DialogFooter>
    </form>
  )
}