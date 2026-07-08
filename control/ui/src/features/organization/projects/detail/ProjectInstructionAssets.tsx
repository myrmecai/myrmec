// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  instructionAssetApi,
  type InstructionAsset,
  type CreateInstructionAssetRequest,
  type InstructionCategory,
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
import { Plus, AlertCircle, MoreVertical } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'

const CATEGORIES: { value: InstructionCategory; label: string }[] = [
  { value: 'CODING_STANDARD', label: 'Coding Standard' },
  { value: 'ARCHITECTURE', label: 'Architecture' },
  { value: 'DOMAIN', label: 'Domain Knowledge' },
  { value: 'STYLE', label: 'Style Guide' },
  { value: 'SAFETY', label: 'Safety' },
  { value: 'GENERAL', label: 'General' },
]

export function ProjectInstructionAssets({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: assets, isLoading, error } = useQuery({
    queryKey: ['instruction-assets', 'project', projectId],
    queryFn: () => instructionAssetApi.list(projectId),
  })

  const createMutation = useMutation({
    mutationFn: async (data: CreateInstructionAssetRequest) => {
      const asset = await instructionAssetApi.create(data)
      await instructionAssetApi.createDraft(asset.id, {
        sourceType: 'INLINE',
        sourceDetails: { content: '' },
        applicability: { CONVERSATION: 'true', WORKFLOW: 'true' },
        availability: 'REQUIRED',
        priority: 100,
      })
      return asset
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets', 'project', projectId] })
      setCreateOpen(false)
      navigate({ to: '/platform/ai-context/instruction-assets/$id', params: { id: data.id } })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: instructionAssetApi.archive,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['instruction-assets', 'project', projectId] }),
  })

  if (isLoading) {
    return <div className="text-muted-foreground">Loading...</div>
  }

  if (error) {
    return (
      <div className="flex items-center justify-center">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load instruction assets</p>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">Project Instruction Assets</h3>
          <p className="text-sm text-muted-foreground">Instruction assets scoped to this project</p>
        </div>
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" /> New Instruction
        </Button>
      </div>

      <Card>
        <CardContent className="pt-4">
          {assets && assets.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Category</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {assets.map((asset) => (
                  <TableRow key={asset.id}>
                    <TableCell className="font-medium">
                      <Link
                        to="/platform/ai-context/instruction-assets/$id"
                        params={{ id: asset.id }}
                        className="hover:underline"
                      >
                        {asset.name}
                      </Link>
                    </TableCell>
                    <TableCell><Badge variant="outline">{asset.category}</Badge></TableCell>
                    <TableCell><Badge variant="outline">{asset.status}</Badge></TableCell>
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
                            <Link to="/platform/ai-context/instruction-assets/$id" params={{ id: asset.id }}>
                              View
                            </Link>
                          </DropdownMenuItem>
                          {asset.status !== 'ARCHIVED' && (
                            <DropdownMenuItem onClick={async () => {
                              const confirmed = await dialogService.showConfirmDialog({
                                title: 'Archive Instruction Asset',
                                message: `Archive "${asset.name}"? This cannot be undone.`,
                                severity: 'warning',
                                type: 'warning',
                                confirmLabel: 'Archive',
                                cancelLabel: 'Cancel',
                              })
                              if (confirmed) deleteMutation.mutate(asset.id)
                            }}>
                              Archive
                            </DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground text-center py-8">
              No project-scoped instruction assets. Click "New Instruction" to create one.
            </p>
          )}
        </CardContent>
      </Card>

      <CreateInstructionDialog
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

function CreateInstructionDialog({
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
  onCreate: (data: CreateInstructionAssetRequest) => void
  isPending: boolean
  error?: string
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<InstructionCategory>('GENERAL')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'PROJECT',
      projectId,
      name,
      description: description || undefined,
      category,
    })
    setName('')
    setDescription('')
    setCategory('GENERAL')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New Instruction</DialogTitle>
          <DialogDescription>Create a project-scoped instruction asset</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {error && <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>}
          <div className="space-y-2">
            <Label htmlFor="ia-name">Name</Label>
            <Input id="ia-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="ia-desc">Description</Label>
            <Textarea id="ia-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="ia-cat">Category</Label>
            <Select value={category} onValueChange={(v) => setCategory(v as InstructionCategory)}>
              <SelectTrigger id="ia-cat"><SelectValue /></SelectTrigger>
              <SelectContent>
                {CATEGORIES.map((c) => (
                  <SelectItem key={c.value} value={c.value}>{c.label}</SelectItem>
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