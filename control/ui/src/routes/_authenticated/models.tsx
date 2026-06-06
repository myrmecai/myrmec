import { createFileRoute } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  modelsApi,
  providersApi,
  type Model,
  type CreateModelRequest,
  type UpdateModelRequest,
  type ModelProviderConfig,
  type DeploymentType,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Plus,
  Pencil,
  Trash2,
  Cloud,
  Server,
  CheckCircle,
  XCircle,
  AlertCircle,
  Loader2,
  Zap,
  Heart,
} from 'lucide-react'

export const Route = createFileRoute('/_authenticated/models')({
  component: ModelsPage,
})

function ModelsPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editModel, setEditModel] = useState<Model | null>(null)
  const [testingModel, setTestingModel] = useState<string | null>(null)

  const { data: models, isLoading, error } = useQuery({
    queryKey: ['models'],
    queryFn: modelsApi.list,
  })

  const { data: providers = [] } = useQuery({
    queryKey: ['providers'],
    queryFn: providersApi.list,
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
    <div className="p-8">
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
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Provider</TableHead>
                <TableHead>Model ID</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-[150px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {models?.map((model) => (
                <TableRow key={model.code}>
                  <TableCell className="font-mono text-sm">{model.code}</TableCell>
                  <TableCell className="font-medium">{model.name}</TableCell>
                  <TableCell>
                    <span className="text-sm">{model.providerName}</span>
                  </TableCell>
                  <TableCell className="font-mono text-sm max-w-[150px] truncate">
                    {model.modelId}
                  </TableCell>
                  <TableCell>
                    {model.deploymentType === 'CLOUD' ? (
                      <span className="inline-flex items-center gap-1 text-blue-600">
                        <Cloud className="h-4 w-4" />
                        Cloud
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-purple-600">
                        <Server className="h-4 w-4" />
                        On-Premise
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <StatusBadge status={model.status} />
                      {model.deploymentType === 'ON_PREMISE' && (
                        <HealthBadge health={model.healthStatus} />
                      )}
                      {model.lastTestStatus === 'SUCCESS' && (
                        <span title="Last test passed">
                          <Zap className="h-4 w-4 text-yellow-500" />
                        </span>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
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
                        onClick={() => {
                          if (confirm('Are you sure you want to delete this model?')) {
                            deleteMutation.mutate(model.code)
                          }
                        }}
                        title="Delete"
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {models?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-muted-foreground">
                    No models configured. Add your first AI model to get started.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
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
    </div>
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
  const [deploymentType, setDeploymentType] = useState<DeploymentType>('CLOUD')
  const [provider, setProvider] = useState<string>('')

  // Filter providers by deployment type
  const filteredProviders = providers.filter(
    (p) =>
      p.code === 'generic' ||
      p.deploymentType === deploymentType
  )

  // Set default provider when deployment type or providers change
  const defaultProvider = filteredProviders.length > 0 ? filteredProviders[0].code : ''
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
      deploymentType,
      modelId: formData.get('modelId') as string,
      apiEndpoint: (formData.get('apiEndpoint') as string) || undefined,
      apiKey: (formData.get('apiKey') as string) || undefined,
      requiresAuth: formData.get('requiresAuth') === 'on',
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
        {/* Deployment Type */}
        <div className="space-y-2">
          <Label>Deployment Type</Label>
          <div className="flex gap-4">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="deploymentType"
                checked={deploymentType === 'CLOUD'}
                onChange={() => {
                  setDeploymentType('CLOUD')
                  const cloudProviders = providers.filter((p) => p.deploymentType === 'CLOUD' || p.code === 'generic')
                  setProvider(cloudProviders.length > 0 ? cloudProviders[0].code : '')
                }}
                className="w-4 h-4"
              />
              <Cloud className="h-4 w-4" />
              Cloud
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="radio"
                name="deploymentType"
                checked={deploymentType === 'ON_PREMISE'}
                onChange={() => {
                  setDeploymentType('ON_PREMISE')
                  const onPremProviders = providers.filter((p) => p.deploymentType === 'ON_PREMISE' || p.code === 'generic')
                  setProvider(onPremProviders.length > 0 ? onPremProviders[0].code : '')
                }}
                className="w-4 h-4"
              />
              <Server className="h-4 w-4" />
              On-Premise
            </label>
          </div>
        </div>

        {/* Code */}
        <div className="space-y-2">
          <Label htmlFor="code">Model Code *</Label>
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
          <Label htmlFor="name">Display Name *</Label>
          <Input id="name" name="name" placeholder="GPT-4 Turbo" required />
        </div>

        {/* Provider */}
        <div className="space-y-2">
          <Label htmlFor="provider">Provider *</Label>
          <select
            id="provider"
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {filteredProviders.map((p) => (
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
          <Label htmlFor="modelId">Model ID *</Label>
          <Input
            id="modelId"
            name="modelId"
            placeholder={deploymentType === 'CLOUD' ? 'gpt-4-turbo' : 'llama3:70b'}
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

        {/* Auth section */}
        {deploymentType === 'ON_PREMISE' && (
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="requiresAuth"
              name="requiresAuth"
              defaultChecked={selectedProvider?.requiresAuth}
              className="w-4 h-4"
            />
            <Label htmlFor="requiresAuth" className="text-sm font-normal cursor-pointer">
              Requires Authentication
            </Label>
          </div>
        )}

        {/* API Key */}
        <div className="space-y-2">
          <Label htmlFor="apiKey">
            API Key {selectedProvider?.requiresAuth ? '*' : '(optional)'}
          </Label>
          <Input
            id="apiKey"
            name="apiKey"
            type="password"
            placeholder="sk-..."
            required={selectedProvider?.requiresAuth && deploymentType === 'CLOUD'}
          />
          {selectedProvider?.docsUrl && (
            <p className="text-xs text-muted-foreground">
              <a href={selectedProvider.docsUrl} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
                View API documentation →
              </a>
            </p>
          )}
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
  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)

    const data: UpdateModelRequest = {
      name: formData.get('name') as string,
      apiEndpoint: (formData.get('apiEndpoint') as string) || undefined,
      apiKey: (formData.get('apiKey') as string) || undefined,
      requiresAuth: formData.get('requiresAuth') === 'on',
      status: formData.get('status') as 'ACTIVE' | 'INACTIVE',
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
        <div className="grid grid-cols-2 gap-4 p-4 bg-muted rounded-lg">
          <div>
            <Label className="text-xs text-muted-foreground">Code</Label>
            <p className="font-mono text-sm">{model.code}</p>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">Provider</Label>
            <p className="text-sm">{model.providerName}</p>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">Deployment</Label>
            <p className="text-sm">{model.deploymentType === 'CLOUD' ? 'Cloud' : 'On-Premise'}</p>
          </div>
          <div>
            <Label className="text-xs text-muted-foreground">Model ID</Label>
            <p className="font-mono text-sm">{model.modelId}</p>
          </div>
        </div>

        {/* Name */}
        <div className="space-y-2">
          <Label htmlFor="name">Display Name</Label>
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

        {/* Requires Auth */}
        {model.deploymentType === 'ON_PREMISE' && (
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="requiresAuth"
              name="requiresAuth"
              defaultChecked={model.requiresAuth}
              className="w-4 h-4"
            />
            <Label htmlFor="requiresAuth" className="text-sm font-normal cursor-pointer">
              Requires Authentication
            </Label>
          </div>
        )}

        {/* API Key */}
        <div className="space-y-2">
          <Label htmlFor="apiKey">API Key (leave empty to keep current)</Label>
          <Input id="apiKey" name="apiKey" type="password" placeholder="••••••••" />
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
