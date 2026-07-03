// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeProviderApi,
  type ProviderType,
  type CreateKnowledgeProviderRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Plus, AlertCircle } from 'lucide-react'
import { TYPE_LABELS, STATUS_COLORS } from '../shared/constants'

export function KnowledgeProvidersList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: providers, isLoading, error } = useQuery({
    queryKey: ['knowledge-providers'],
    queryFn: knowledgeProviderApi.list,
  })

  const createMutation = useMutation({
    mutationFn: knowledgeProviderApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] })
      setCreateOpen(false)
    },
  })

  const disableMutation = useMutation({
    mutationFn: knowledgeProviderApi.disable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] }),
  })

  const reenableMutation = useMutation({
    mutationFn: knowledgeProviderApi.reenable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-providers'] }),
  })

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load knowledge providers</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Knowledge Providers</h1>
          <p className="text-muted-foreground">Versioned knowledge provider definitions with connection configs</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Provider
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Providers</CardTitle>
          <CardDescription>Knowledge providers with versioned connection configurations</CardDescription>
        </CardHeader>
        <CardContent>
          {providers && providers.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Published</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {providers.map((provider) => (
                  <TableRow key={provider.id}>
                    <TableCell className="font-medium">
                      <Link to="/platform/ai-context/knowledge-providers/$id" params={{ id: provider.id }} className="hover:underline">
                        {provider.name}
                      </Link>
                      {provider.description && (
                        <span className="text-xs text-muted-foreground block">{provider.description}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{TYPE_LABELS[provider.type] || provider.type}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[provider.status] || 'bg-gray-400'}`} />
                        <span className="text-sm">{provider.status}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {provider.publishedAt
                        ? new Date(provider.publishedAt).toLocaleDateString()
                        : '—'}
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        {provider.status === 'ACTIVE' && (
                          <Button size="sm" variant="outline" onClick={() => disableMutation.mutate(provider.id)}>
                            Disable
                          </Button>
                        )}
                        {provider.status === 'DISABLED' && (
                          <Button size="sm" variant="outline" onClick={() => reenableMutation.mutate(provider.id)}>
                            Re-enable
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground text-center py-8">No knowledge providers yet.</p>
          )}
        </CardContent>
      </Card>

      <CreateKnowledgeProviderDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateKnowledgeProviderDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateKnowledgeProviderRequest) => void
  isPending: boolean
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<ProviderType>('MANAGED')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'ORGANIZATION',
      name,
      description: description || undefined,
      type,
    })
    setName('')
    setDescription('')
    setType('MANAGED')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Knowledge Provider</DialogTitle>
          <DialogDescription>Define a new knowledge provider with versioned configs</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Company Knowledge Base" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Brief description" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="type">Provider Type</Label>
            <Select value={type} onValueChange={(v) => setType(v as ProviderType)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(TYPE_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending || !name}>{isPending ? 'Creating...' : 'Create'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}