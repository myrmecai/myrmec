// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  knowledgeSourceApi,
  knowledgeProviderApi,
  type CreateKnowledgeSourceRequest,
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
import { STATUS_COLORS, AVAILABILITY_LABELS } from '../shared/constants'

export function KnowledgeSourcesList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: sources, isLoading, error } = useQuery({
    queryKey: ['knowledge-sources'],
    queryFn: knowledgeSourceApi.list,
  })

  const createMutation = useMutation({
    mutationFn: knowledgeSourceApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-sources'] })
      setCreateOpen(false)
    },
  })

  const disableMutation = useMutation({
    mutationFn: knowledgeSourceApi.disable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-sources'] }),
  })

  const archiveMutation = useMutation({
    mutationFn: knowledgeSourceApi.archive,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['knowledge-sources'] }),
  })

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load knowledge sources</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Knowledge Sources</h1>
          <p className="text-muted-foreground">Configured knowledge sources linked to provider versions</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Source
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Sources</CardTitle>
          <CardDescription>Knowledge sources with availability and priority settings</CardDescription>
        </CardHeader>
        <CardContent>
          {sources && sources.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Availability</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sources.map((source) => (
                  <TableRow key={source.id}>
                    <TableCell className="font-medium">
                      <Link to="/platform/ai-context/knowledge-sources/$id" params={{ id: source.id }} className="hover:underline">
                        {source.name}
                      </Link>
                      {source.description && (
                        <span className="text-xs text-muted-foreground block">{source.description}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{AVAILABILITY_LABELS[source.availability] || source.availability}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">{source.priority}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[source.status] || 'bg-gray-400'}`} />
                        <span className="text-sm">{source.status}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        {source.status === 'ACTIVE' && (
                          <Button size="sm" variant="outline" onClick={() => disableMutation.mutate(source.id)}>
                            Disable
                          </Button>
                        )}
                        {(source.status === 'ACTIVE' || source.status === 'DISABLED') && (
                          <Button size="sm" variant="ghost" className="text-destructive" onClick={() => archiveMutation.mutate(source.id)}>
                            Archive
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground text-center py-8">No knowledge sources yet.</p>
          )}
        </CardContent>
      </Card>

      <CreateKnowledgeSourceDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateKnowledgeSourceDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateKnowledgeSourceRequest) => void
  isPending: boolean
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [availability, setAvailability] = useState('REQUIRED')
  const [priority, setPriority] = useState('100')

  const { data: providers } = useQuery({
    queryKey: ['knowledge-providers'],
    queryFn: knowledgeProviderApi.list,
  })

  const activeProviders = providers?.filter((p) => p.status === 'ACTIVE' && p.currentVersionId) || []

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!activeProviders.length) return
    onCreate({
      scope: 'ORGANIZATION',
      name,
      description: description || undefined,
      providerVersionId: activeProviders[0]?.currentVersionId || '',
      availability,
      priority: parseInt(priority) || 100,
    })
    setName('')
    setDescription('')
    setAvailability('REQUIRED')
    setPriority('100')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Knowledge Source</DialogTitle>
          <DialogDescription>Define a new knowledge source linked to a provider</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Product Docs Source" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Brief description" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="availability">Availability</Label>
            <Select value={availability} onValueChange={setAvailability}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                {Object.entries(AVAILABILITY_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>{label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="priority">Priority (lower = higher priority)</Label>
            <Input id="priority" type="number" value={priority} onChange={(e) => setPriority(e.target.value)} placeholder="100" />
          </div>
          {activeProviders.length === 0 && (
            <p className="text-sm text-yellow-600">No active knowledge providers with published versions. Create and publish a provider first.</p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button type="submit" disabled={isPending || !name || activeProviders.length === 0}>
              {isPending ? 'Creating...' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}