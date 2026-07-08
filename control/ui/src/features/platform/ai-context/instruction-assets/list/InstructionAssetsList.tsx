// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Link, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useMemo } from 'react'
import { type ColumnDef } from '@tanstack/react-table'
import {
  instructionAssetApi,
  type InstructionAsset,
  type InstructionCategory,
  type CreateInstructionAssetRequest,
  type SourceType,
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Plus, AlertCircle, MoreVertical, Eye, Power, PowerOff, Archive } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dialogService } from '@/services/dialog-service'
import DataTable2 from '@/components/data-table2/data-table2'
import { SortedColumnHeader } from '@/components/data-table2/sorted-column-header'
import { CATEGORY_LABELS, STATUS_COLORS } from '../shared/constants'

export function InstructionAssetsList() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  const { data: assets, isLoading } = useQuery({
    queryKey: ['instruction-assets'],
    queryFn: () => instructionAssetApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: async (data: CreateInstructionAssetRequest & { sourceType?: SourceType }) => {
      // Create the parent, then auto-create a Draft (Pattern 4 §Rule 4)
      const asset = await instructionAssetApi.create(data)
      const sourceType = data.sourceType ?? 'INLINE'
      await instructionAssetApi.createDraft(asset.id, {
        sourceType,
        sourceDetails: sourceType === 'INLINE' ? { content: '' } : undefined,
        applicability: { CONVERSATION: 'true', WORKFLOW: 'true' },
        availability: 'REQUIRED',
        priority: 100,
      })
      return asset
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
      setCreateOpen(false)
      setCreateError(null)
      // Show toast (Pattern 4 §Rule 5)
      setToastMessage('Instruction Asset created — please complete and publish.')
      setTimeout(() => setToastMessage(null), 5000)
      // Navigate to detail page in edit mode (Pattern 4 §Rule 3, 4)
      navigate({ to: '/platform/ai-context/instruction-assets/$id', params: { id: data.id } })
    },
    onError: (error: any) => {
      const details = error?.error?.details
      if (Array.isArray(details) && details.length > 0 && details[0]?.message) {
        setCreateError(details[0].message)
      } else {
        setCreateError(error?.error?.message ?? 'Failed to create instruction asset')
      }
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

  const archiveMutation = useMutation({
    mutationFn: instructionAssetApi.archive,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['instruction-assets'] })
    },
  })

  const filteredAssets = useMemo(
    () => (assets ?? []).filter((a) => a.status !== 'ARCHIVED'),
    [assets],
  )

  const columns: ColumnDef<InstructionAsset>[] = useMemo(() => [
    {
      accessorKey: 'name',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Name" />
      ),
      cell: ({ row }) => (
        <div className="font-medium">
          <Link to="/platform/ai-context/instruction-assets/$id" params={{ id: row.original.id }} className="hover:underline">
            {row.original.name}
          </Link>
        </div>
      ),
    },
    {
      accessorKey: 'category',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Category" />
      ),
      cell: ({ row }) => (
        <Badge variant="outline">{CATEGORY_LABELS[row.original.category] || row.original.category}</Badge>
      ),
    },
    {
      id: 'sourceType',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Source Type" />
      ),
      cell: ({ row }) => (
        <span className="text-sm">{row.original.sourceType ?? '—'}</span>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Status" />
      ),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <div className={`h-2 w-2 rounded-full ${STATUS_COLORS[row.original.status] || 'bg-gray-400'}`} />
          <span className="text-sm">{row.original.status}</span>
        </div>
      ),
    },
    {
      id: 'version',
      header: 'Version',
      enableSorting: false,
      cell: ({ row }) => <DraftStatusBadge assetId={row.original.id} />,
    },
    {
      accessorKey: 'updatedAt',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Last Updated At" />
      ),
      cell: ({ row }) => (
        <span className="text-sm text-muted-foreground">
          {row.original.updatedAt ? new Date(row.original.updatedAt).toLocaleString() : '—'}
        </span>
      ),
    },
    {
      accessorKey: 'updatedBy',
      header: ({ table, column }) => (
        <SortedColumnHeader table={table} column={column} title="Last Updated By" />
      ),
      cell: ({ row }) => (
        <span className="text-sm text-muted-foreground">{row.original.updatedBy ?? '—'}</span>
      ),
    },
    {
      id: 'actions',
      header: 'Actions',
      enableSorting: false,
      cell: ({ row }) => {
        const asset = row.original
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0" aria-label="More actions">
                <MoreVertical className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem asChild>
                <Link to="/platform/ai-context/instruction-assets/$id" params={{ id: asset.id }}>
                  <Eye className="h-4 w-4 mr-2" /> View
                </Link>
              </DropdownMenuItem>
              {asset.status === 'ACTIVE' && (
                <DropdownMenuItem onClick={() => disableMutation.mutate(asset.id)}>
                  <PowerOff className="h-4 w-4 mr-2" /> Disable
                </DropdownMenuItem>
              )}
              {asset.status === 'DISABLED' && (
                <DropdownMenuItem onClick={() => reenableMutation.mutate(asset.id)}>
                  <Power className="h-4 w-4 mr-2" /> Enable
                </DropdownMenuItem>
              )}
              {asset.status !== 'ARCHIVED' && (
                <DropdownMenuItem
                  onClick={async () => {
                    const confirmed = await dialogService.showConfirmDialog({
                      title: 'Archive Instruction Asset',
                      message: `Archive "${asset.name}"? This can be restored later.`,
                      severity: 'warning',
                      type: 'warning',
                      confirmLabel: 'Archive',
                      cancelLabel: 'Cancel',
                    })
                    if (confirmed) archiveMutation.mutate(asset.id)
                  }}
                  disabled={asset.status === 'ACTIVE'}
                >
                  <Archive className="h-4 w-4 mr-2" /> Archive
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )
      },
    },
  ], [disableMutation, reenableMutation, archiveMutation])

  return (
    <ContentAreaLayout>
    <div className="space-y-6">
      {toastMessage && (
        <div
          className="fixed bottom-4 right-4 z-50 rounded-lg border bg-background p-4 shadow-lg"
          role="status"
          aria-live="polite"
        >
          <p className="text-sm">{toastMessage}</p>
        </div>
      )}
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
          <DataTable2
            columns={columns}
            data={filteredAssets}
            pagination={false}
            loading={isLoading}
            showRowSelection={false}
          />
        </CardContent>
      </Card>

      <CreateInstructionAssetDialog
        open={createOpen}
        onOpenChange={(v) => { setCreateOpen(v); if (v) setCreateError(null) }}
        onCreate={(data) => createMutation.mutate(data)}
        isPending={createMutation.isPending}
        error={createError}
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
  error,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreate: (data: CreateInstructionAssetRequest & { sourceType?: SourceType }) => void
  isPending: boolean
  error?: string | null
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<InstructionCategory>('STANDARD')
  const [sourceType, setSourceType] = useState<SourceType>('INLINE')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onCreate({
      scope: 'ORGANIZATION',
      name,
      description: description || undefined,
      category,
      sourceType,
    })
    setName('')
    setDescription('')
    setCategory('STANDARD')
    setSourceType('INLINE')
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
              <SelectTrigger id="category">
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
          <div className="space-y-2">
            <Label htmlFor="sourceType">Source Type</Label>
            <Select value={sourceType} onValueChange={(v) => setSourceType(v as SourceType)}>
              <SelectTrigger id="sourceType"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="INLINE">Inline (text content)</SelectItem>
                <SelectItem value="GIT">Git (from repository)</SelectItem>
              </SelectContent>
            </Select>
          </div>
          {error && (
            <div className="flex items-center gap-2 text-sm text-destructive">
              <AlertCircle className="h-4 w-4" />
              <span>{error}</span>
            </div>
          )}
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