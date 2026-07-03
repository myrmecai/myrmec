// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  connectionConfigApi,
  type ConnectionType,
  type CreateConnectionConfigRequest,
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

export function ConnectionsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: configs, isLoading, error } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: connectionConfigApi.list,
  })

  const createMutation = useMutation({
    mutationFn: connectionConfigApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['connection-configs'] })
      setCreateOpen(false)
    },
  })

  const disableMutation = useMutation({
    mutationFn: connectionConfigApi.disable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  const reenableMutation = useMutation({
    mutationFn: connectionConfigApi.reenable,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['connection-configs'] }),
  })

  if (isLoading) {
    return <div className="flex items-center justify-center h-full"><p className="text-muted-foreground">Loading...</p></div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load connection configs</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Connection Configs</h1>
          <p className="text-muted-foreground">Global connection definitions for GIT, HTTP, S3, and more</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Connection
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization Connections</CardTitle>
          <CardDescription>Reusable connection definitions with versioned configs</CardDescription>
        </CardHeader>
        <CardContent>
          {configs && configs.length > 0 ? (
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
                {configs.map((config) => (
                  <TableRow key={config.id}>
                    <TableCell className="font-medium">
                      <Link to="/platform/connections/$id" params={{ id: config.id }} className="hover:underline">
                        {config.name}
                      </Link>
                      {config.description && (
                        <span className="text-xs text-muted-foreground block">{config.description}</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{TYPE_LABELS[config.type] || config.type}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[config.status] || 'bg-gray-400'}`} />
                        <span className="text-sm">{config.status}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        {config.status === 'ACTIVE' && (
                          <Button size="sm" variant="outline" onClick={() => disableMutation.mutate(config.id)}>
                            Disable
                          </Button>
                        )}
                        {config.status === 'DISABLED' && (
                          <Button size="sm" variant="outline" onClick={() => reenableMutation.mutate(config.id)}>
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
            <p className="text-muted-foreground text-center py-8">No connection configs yet.</p>
          )}
        </CardContent>
      </Card>

      <CreateConnectionConfigDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateConnectionConfigDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateConnectionConfigRequest) => void
  isPending: boolean
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<ConnectionType>('GIT')

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
    setType('GIT')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Connection Config</DialogTitle>
          <DialogDescription>Define a new global connection</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Company Git Repo" required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Brief description" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="type">Connection Type</Label>
            <Select value={type} onValueChange={(v) => setType(v as ConnectionType)}>
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