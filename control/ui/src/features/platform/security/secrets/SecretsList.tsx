// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  globalSecretsApi,
  type Secret,
  type CredentialType,
  type CreateSecretRequest,
  type SecretPayload,
} from '@/lib/api'
import {
  CREDENTIAL_TYPE_OPTIONS,
  emptyPayloadFor,
  SecretCredentialFields,
} from '@/lib/secrets-form'
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Pencil, Trash2, KeyRound } from 'lucide-react'

export function SecretsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editSecret, setEditSecret] = useState<Secret | null>(null)
  const [editMetaSecret, setEditMetaSecret] = useState<Secret | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const {
    data: secrets,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['global-secrets'],
    queryFn: () => globalSecretsApi.list(),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['global-secrets'] })
  }

  const createMutation = useMutation({
    mutationFn: (data: CreateSecretRequest) => globalSecretsApi.create(data),
    onSuccess: () => {
      invalidate()
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: SecretPayload }) =>
      globalSecretsApi.update(id, { payload }),
    onSuccess: () => {
      invalidate()
      setEditSecret(null)
    },
  })

  const updateMetadataMutation = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      globalSecretsApi.updateMetadata(id, name),
    onSuccess: () => {
      invalidate()
      setEditMetaSecret(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => globalSecretsApi.delete(id),
    onSuccess: () => {
      setDeleteError(null)
      invalidate()
    },
    onError: (error: Error) => {
      setDeleteError(error.message || 'Failed to delete secret. It may be in use.')
    },
  })

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading secrets...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load secrets</div>
      </div>
    )
  }

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-2">
            <KeyRound className="h-7 w-7" />
            Global Secrets
          </h1>
          <p className="text-muted-foreground">
            Credentials shared across all projects. PLATFORM_ADMIN only.
          </p>
        </div>
      </div>

      {deleteError && (
        <div className="mb-4 p-3 text-sm text-destructive bg-destructive/10 rounded-md flex items-center justify-between">
          <span>{deleteError}</span>
          <Button variant="ghost" size="sm" onClick={() => setDeleteError(null)}>
            Dismiss
          </Button>
        </div>
      )}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>All Global Secrets</CardTitle>
            <CardDescription>
              {secrets?.length || 0} secret(s) configured. Encrypted at rest; values never returned by the API.
            </CardDescription>
          </div>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger asChild>
              <Button>
                <Plus className="h-4 w-4 mr-2" />
                New Global Secret
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-xl">
              <CreateSecretForm
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
                <TableHead>Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Created By</TableHead>
                <TableHead>Updated</TableHead>
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {secrets?.map((secret) => (
                <TableRow key={secret.id}>
                  <TableCell className="font-medium">{secret.name}</TableCell>
                  <TableCell>
                    <span className="font-mono text-xs">{secret.type}</span>
                  </TableCell>
                  <TableCell className="text-xs">{secret.createdByEmail ?? '-'}</TableCell>
                  <TableCell className="text-xs">
                    {new Date(secret.updatedAt).toLocaleString()}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditMetaSecret(secret)}
                        title="Edit metadata"
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditSecret(secret)}
                        title="Rotate value"
                      >
                        <KeyRound className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          if (confirm(`Delete global secret "${secret.name}"?`)) {
                            deleteMutation.mutate(secret.id)
                          }
                        }}
                        title="Delete"
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {(!secrets || secrets.length === 0) && (
                <TableRow>
                  <TableCell colSpan={5} className="text-center text-muted-foreground py-8">
                    No global secrets yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={!!editSecret} onOpenChange={(open) => !open && setEditSecret(null)}>
        <DialogContent className="max-w-xl">
          {editSecret && (
            <RotateSecretForm
              secret={editSecret}
              onSubmit={(payload) => updateMutation.mutate({ id: editSecret.id, payload })}
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={!!editMetaSecret} onOpenChange={(open) => !open && setEditMetaSecret(null)}>
        <DialogContent className="max-w-xl">
          {editMetaSecret && (
            <EditMetadataForm
              secret={editMetaSecret}
              onSubmit={(name) => updateMetadataMutation.mutate({ id: editMetaSecret.id, name })}
              isLoading={updateMetadataMutation.isPending}
              error={updateMetadataMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface CreateSecretFormProps {
  onSubmit: (data: CreateSecretRequest) => void
  isLoading: boolean
  error?: string
}

function CreateSecretForm({ onSubmit, isLoading, error }: CreateSecretFormProps) {
  const [name, setName] = useState('')
  const [type, setType] = useState<CredentialType>('BEARER_TOKEN')
  const [payload, setPayload] = useState<SecretPayload>(emptyPayloadFor('BEARER_TOKEN'))

  const handleTypeChange = (next: CredentialType) => {
    setType(next)
    setPayload(emptyPayloadFor(next))
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({ name: name.trim(), type, payload })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Global Secret</DialogTitle>
        <DialogDescription>
          Global secrets are visible to every project. Use sparingly.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="secret-name">Name *</Label>
          <Input
            id="secret-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., shared-github-pat"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="secret-type">Type *</Label>
          <Select value={type} onValueChange={(v) => handleTypeChange(v as CredentialType)}>
            <SelectTrigger id="secret-type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {CREDENTIAL_TYPE_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            {CREDENTIAL_TYPE_OPTIONS.find((o) => o.value === type)?.hint}
          </p>
        </div>
        <SecretCredentialFields payload={payload} onChange={setPayload} />
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Create Secret'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface RotateSecretFormProps {
  secret: Secret
  onSubmit: (payload: SecretPayload) => void
  isLoading: boolean
  error?: string
}

function RotateSecretForm({ secret, onSubmit, isLoading, error }: RotateSecretFormProps) {
  const [payload, setPayload] = useState<SecretPayload>(emptyPayloadFor(secret.type))

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit(payload)
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Rotate Global Secret</DialogTitle>
        <DialogDescription>
          Replace the encrypted value for <span className="font-mono">{secret.name}</span> (type: {secret.type}).
          The credential type cannot be changed after creation.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <SecretCredentialFields payload={payload} onChange={setPayload} />
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Update Secret'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditMetadataFormProps {
  secret: Secret
  onSubmit: (name: string) => void
  isLoading: boolean
  error?: string
}

function EditMetadataForm({ secret, onSubmit, isLoading, error }: EditMetadataFormProps) {
  const [name, setName] = useState(secret.name)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit(name.trim())
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Secret Metadata</DialogTitle>
        <DialogDescription>
          Update the name for this secret. The type cannot be changed after creation.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="meta-name">Name *</Label>
          <Input
            id="meta-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., shared-github-pat"
            required
          />
        </div>
        <div className="space-y-2">
          <Label>Type</Label>
          <div className="text-sm text-muted-foreground font-mono">{secret.type}</div>
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