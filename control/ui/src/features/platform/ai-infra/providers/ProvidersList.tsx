// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  providersApi,
  type CreateModelProviderRequest,
  type DeploymentType,
  type ModelProviderConfig,
  type ModelStatus,
  type UpdateModelProviderRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Cloud, Pencil, Plus, Server, Trash2 } from 'lucide-react'

const DEPLOYMENT_TYPES: DeploymentType[] = ['CLOUD', 'ON_PREMISE']
const STATUSES: ModelStatus[] = ['ACTIVE', 'INACTIVE']

export function ProvidersList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<ModelProviderConfig | null>(null)

  const {
    data: providers,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['providers-admin'],
    queryFn: () => providersApi.listAll(),
  })

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
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Deployment</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>System</TableHead>
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {providers?.map((p) => (
                <TableRow key={p.code}>
                  <TableCell className="font-mono text-xs">{p.code}</TableCell>
                  <TableCell>{p.name}</TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1.5 text-xs">
                      {p.deploymentType === 'CLOUD' ? (
                        <Cloud className="h-3.5 w-3.5" />
                      ) : (
                        <Server className="h-3.5 w-3.5" />
                      )}
                      {p.deploymentType}
                    </div>
                  </TableCell>
                  <TableCell>
                    {p.status === 'ACTIVE' ? (
                      <span className="text-xs px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-700">
                        active
                      </span>
                    ) : (
                      <span className="text-xs px-2 py-0.5 rounded bg-muted text-muted-foreground">
                        inactive
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    {p.isSystem ? (
                      <span className="text-xs px-2 py-0.5 rounded bg-blue-500/10 text-blue-700">
                        system
                      </span>
                    ) : (
                      <span className="text-xs text-muted-foreground">
                        custom
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
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
                        disabled={p.isSystem}
                        onClick={() => {
                          if (
                            window.confirm(
                              `Delete provider "${p.code}"? Only non-system providers with no attached models can be deleted.`,
                            )
                          ) {
                            deleteMutation.mutate(p.code)
                          }
                        }}
                        title={
                          p.isSystem
                            ? 'System providers cannot be deleted'
                            : 'Delete'
                        }
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {(!providers || providers.length === 0) && (
                <TableRow>
                  <TableCell
                    colSpan={6}
                    className="text-center text-muted-foreground py-8"
                  >
                    No providers configured.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

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

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      code: code.trim(),
      name: name.trim(),
      baseUrl: baseUrl.trim() || null,
      deploymentType,
      requiresAuth,
    })
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
          <Label htmlFor="provider-code">Code</Label>
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
          <Label htmlFor="provider-name">Name</Label>
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
            placeholder="https://api.example.invalid"
          />
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
                Yes (Bearer token by default)
              </Label>
            </div>
          </div>
        </div>
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
  const [status, setStatus] = useState<ModelStatus>(provider.status)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      name,
      baseUrl: baseUrl.trim() || null,
      status,
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
          />
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
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Save'}
        </Button>
      </DialogFooter>
    </form>
  )
}