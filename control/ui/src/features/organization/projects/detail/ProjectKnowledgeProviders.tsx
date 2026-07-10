// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeProviderApi,
  type KnowledgeProvider,
  type CreateKnowledgeProviderRequest,
  type ProviderType,
} from '@/lib/api'
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Plus, AlertCircle, MoreVertical } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'
import { TYPE_LABELS } from '@/features/platform/ai-context/knowledge-providers/shared/constants'

export function ProjectKnowledgeProviders({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: providers, isLoading, error } = useQuery({
    queryKey: ['knowledge-providers', 'project', projectId],
    queryFn: () => knowledgeProviderApi.list(projectId),
  })

  const createMutation = useMutation({
    mutationFn: knowledgeProviderApi.create,
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers', 'project', projectId] })
      setCreateOpen(false)
      navigate({ to: '/projects/$projectId/knowledge-providers/$id', params: { projectId, id: data.id } })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: knowledgeProviderApi.delete,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-providers', 'project', projectId] }),
  })

  if (isLoading) {
    return <div className="text-muted-foreground">Loading...</div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load knowledge providers</p>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">Project Knowledge Providers</h3>
          <p className="text-sm text-muted-foreground">Knowledge providers scoped to this project</p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" /> New Provider
        </Button>
      </div>

      <Card>
        <CardContent className="pt-4">
          {providers && providers.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {providers.map((provider) => (
                  <TableRow key={provider.id}>
                    <TableCell className="font-medium">
                      <Link
                        to="/projects/$projectId/knowledge-providers/$id"
                        params={{ projectId, id: provider.id }}
                        className="hover:underline"
                      >
                        {provider.name}
                      </Link>
                    </TableCell>
                    <TableCell><Badge variant="outline">{TYPE_LABELS[provider.type] || provider.type}</Badge></TableCell>
                    <TableCell><Badge variant="outline">{provider.status}</Badge></TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button size="sm" variant="ghost" className="h-8 w-8 p-0">
                            <span className="sr-only">More actions</span>
                            <MoreVertical className="h-4 w-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem asChild>
                            <Link to="/projects/$projectId/knowledge-providers/$id" params={{ projectId, id: provider.id }}>
                              View
                            </Link>
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={async () => {
                            const confirmed = await dialogService.showConfirmDialog({
                              title: 'Delete Provider',
                              message: `Delete "${provider.name}"? This cannot be undone.`,
                              severity: 'warning',
                              type: 'warning',
                              confirmLabel: 'Delete',
                              cancelLabel: 'Cancel',
                            })
                            if (confirmed) deleteMutation.mutate(provider.id)
                          }}>
                            Delete
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground text-center py-8">
              No project-scoped knowledge providers. Click "New Provider" to create one.
            </p>
          )}
        </CardContent>
      </Card>

      <CreateProviderDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        projectId={projectId}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
        error={createMutation.error?.message}
      />
    </div>
  )
}

function CreateProviderDialog({
  open,
  onOpenChange,
  projectId,
  onCreate,
  isPending,
  error,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  onCreate: (data: CreateKnowledgeProviderRequest) => void
  isPending: boolean
  error?: string
}) {
  const [name, setName] = useState('')
  const [type, setType] = useState<ProviderType>('EXTERNAL')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'PROJECT',
      projectId,
      name,
      type,
    })
    setName('')
    setType('EXTERNAL')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New Provider</DialogTitle>
          <DialogDescription>Create a project-scoped knowledge provider</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {error && <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>}
          <div className="space-y-2">
            <Label htmlFor="kp-name">Name</Label>
            <Input id="kp-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="kp-type">Provider Type</Label>
            <Select value={type} onValueChange={(v) => setType(v as ProviderType)}>
              <SelectTrigger id="kp-type"><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(TYPE_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending || !name}>
              {isPending ? 'Creating...' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}