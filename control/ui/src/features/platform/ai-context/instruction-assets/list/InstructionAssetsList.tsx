// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  instructionAssetApi,
  type InstructionCategory,
  type CreateInstructionAssetRequest,
  type SourceType,
  type Availability,
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
import { CATEGORY_LABELS, STATUS_COLORS } from '../shared/constants'

export function InstructionAssetsList() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const { data: assets, isLoading, error } = useQuery({
    queryKey: ['instruction-assets'],
    queryFn: instructionAssetApi.list,
  })

  const createMutation = useMutation({
    mutationFn: instructionAssetApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
      setCreateOpen(false)
    },
  })

  const disableMutation = useMutation({
    mutationFn: instructionAssetApi.disable,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
    },
  })

  const reenableMutation = useMutation({
    mutationFn: instructionAssetApi.reenable,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
    },
  })

  const createDraftMutation = useMutation({
    mutationFn: ({ id }: { id: string }) =>
      instructionAssetApi.createDraft(id, {
        sourceType: 'INLINE' as SourceType,
        applicability: { scope: 'ORGANIZATION' },
        availability: 'REQUIRED' as Availability,
        priority: 100,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
      queryClient.invalidateQueries({ queryKey: ['instruction-asset-drafts'] })
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-muted-foreground">Loading instruction assets...</p>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load instruction assets</p>
        </div>
      </div>
    )
  }

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Instruction Assets</h1>
          <p className="text-muted-foreground">AI instructions with categories, source types, and applicability</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          New Instruction
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Organization-Scoped Instructions</CardTitle>
          <CardDescription>Instructions available to all projects in the organization</CardDescription>
        </CardHeader>
        <CardContent>
          {assets && assets.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Category</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {assets.map((asset) => (
                  <TableRow key={asset.id}>
                    <TableCell className="font-medium">
                      <Link to="/platform/ai-context/instruction-assets/$id" params={{ id: asset.id }} className="hover:underline">
                        {asset.name}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{CATEGORY_LABELS[asset.category] || asset.category}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[asset.status] || 'bg-gray-400'}`} />
                        <span className="text-sm">{asset.status}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <DraftStatusBadge assetId={asset.id} />
                    </TableCell>
                    <TableCell>
                      <div className="flex gap-2">
                        {asset.status === 'INCOMPLETE' && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => createDraftMutation.mutate({ id: asset.id })}
                          >
                            Create Draft
                          </Button>
                        )}
                        {asset.status === 'ACTIVE' && (
                          <>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => createDraftMutation.mutate({ id: asset.id })}
                            >
                              New Draft
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => disableMutation.mutate(asset.id)}
                            >
                              Disable
                            </Button>
                          </>
                        )}
                        {asset.status === 'DISABLED' && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => reenableMutation.mutate(asset.id)}
                          >
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
            <p className="text-muted-foreground text-center py-8">No instruction assets yet. Create one to get started.</p>
          )}
        </CardContent>
      </Card>

      <CreateInstructionAssetDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
      />
    </div>
    </ContentAreaLayout>
  )
}

function CreateInstructionAssetDialog({
  open,
  onOpenChange,
  onCreate,
  isPending,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateInstructionAssetRequest) => void
  isPending: boolean
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<InstructionCategory>('STANDARD')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'ORGANIZATION',
      name,
      description: description || undefined,
      category,
    })
    setName('')
    setDescription('')
    setCategory('STANDARD')
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Instruction Asset</DialogTitle>
          <DialogDescription>Define a new AI instruction for the organization</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g., Code Review Guidelines"
              required
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Brief description of this instruction"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="category">Category</Label>
            <Select value={category} onValueChange={(v) => setCategory(v as InstructionCategory)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {Object.entries(CATEGORY_LABELS).map(([value, label]) => (
                  <SelectItem key={value} value={value}>
                    {label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isPending || !name}>
              {isPending ? 'Creating...' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Shows the draft version status for an instruction asset.
 * Fetches the draft version and displays publish/discard actions if a draft exists.
 */
function DraftStatusBadge({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient()
  const { data: draft } = useQuery({
    queryKey: ['instruction-asset-drafts', assetId],
    queryFn: () => instructionAssetApi.getDraftVersion(assetId),
    retry: false,
  })

  const publishMutation = useMutation({
    mutationFn: () => instructionAssetApi.publish(assetId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
      queryClient.invalidateQueries({ queryKey: ['instruction-asset-drafts', assetId] })
    },
  })

  const discardMutation = useMutation({
    mutationFn: () => instructionAssetApi.discardDraft(assetId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
      queryClient.invalidateQueries({ queryKey: ['instruction-asset-drafts', assetId] })
    },
  })

  if (!draft) {
    return <span className="text-xs text-muted-foreground">No draft</span>
  }

  return (
    <div className="flex items-center gap-2">
      <Badge variant="secondary" className="text-xs">Draft v{draft.versionNumber}</Badge>
      <Button
        size="sm"
        variant="outline"
        className="h-6 text-xs"
        onClick={() => publishMutation.mutate()}
        disabled={publishMutation.isPending}
      >
        Publish
      </Button>
      <Button
        size="sm"
        variant="ghost"
        className="h-6 text-xs text-destructive"
        onClick={() => discardMutation.mutate()}
        disabled={discardMutation.isPending}
      >
        Discard
      </Button>
    </div>
  )
}