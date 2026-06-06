import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import {
  workflowsApi,
  workflowsListApi,
  type Workflow,
  type WorkflowListItem,
  type WorkflowStatus,
  type RequestStatus,
  type CreateWorkflowRequest,
  type UpdateWorkflowRequest,
  type WorkflowSortField,
  type WorkflowListParams,
  type SortDirection,
} from '@/lib/api'
import {
  workflowMetadataSchema,
  type WorkflowMetadataFormValues,
} from '@/lib/workflow-schema'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Badge } from '@/components/ui/badge'
import { SortableTableHeader } from '@/components/ui/sortable-table-header'
import {
  Plus,
  Pencil,
  Trash2,
  Workflow as WorkflowIcon,
  AlertCircle,
  Play,
  Archive,
  Eye,
  Settings2,
  Search,
  Filter,
  X,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'

type WorkflowsSearch = {
  status?: WorkflowStatus[]
  projectId?: string[]
  search?: string
  lastRunStatus?: (RequestStatus | 'NEVER')[]
  createdFrom?: string
  createdTo?: string
  lastRunFrom?: string
  lastRunTo?: string
  sort?: WorkflowSortField
  dir?: SortDirection
  page?: number
  size?: number
}

const DEFAULT_STATUSES: WorkflowStatus[] = ['DRAFT', 'PUBLISHED']
const ALL_STATUSES: WorkflowStatus[] = ['DRAFT', 'PUBLISHED', 'DISABLED', 'ARCHIVED']
const ALL_RUN_STATUSES: (RequestStatus | 'NEVER')[] = [
  'PENDING',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'TIMEOUT',
  'NEVER',
]
const PAGE_SIZES = [25, 50, 100]

function asStringArray(v: unknown): string[] | undefined {
  if (Array.isArray(v)) return v.filter((x): x is string => typeof x === 'string')
  if (typeof v === 'string' && v.length > 0) return v.split(',').filter(Boolean)
  return undefined
}

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined
}

function asNumber(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.length > 0) {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  return undefined
}

export const Route = createFileRoute('/_authenticated/workflows/')({
  component: WorkflowsPage,
  validateSearch: (search: Record<string, unknown>): WorkflowsSearch => ({
    status: asStringArray(search.status) as WorkflowStatus[] | undefined,
    projectId: asStringArray(search.projectId),
    search: asString(search.search),
    lastRunStatus: asStringArray(search.lastRunStatus) as
      | (RequestStatus | 'NEVER')[]
      | undefined,
    createdFrom: asString(search.createdFrom),
    createdTo: asString(search.createdTo),
    lastRunFrom: asString(search.lastRunFrom),
    lastRunTo: asString(search.lastRunTo),
    sort: asString(search.sort) as WorkflowSortField | undefined,
    dir: asString(search.dir) as SortDirection | undefined,
    page: asNumber(search.page),
    size: asNumber(search.size),
  }),
})

const statusColors: Record<WorkflowStatus, string> = {
  DRAFT: 'bg-gray-500',
  PUBLISHED: 'bg-green-600',
  DISABLED: 'bg-yellow-600',
  ARCHIVED: 'bg-red-600',
}

const statusLabels: Record<WorkflowStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  DISABLED: 'Disabled',
  ARCHIVED: 'Archived',
}

const runStatusColors: Record<RequestStatus, string> = {
  PENDING: 'bg-gray-400',
  RUNNING: 'bg-blue-600',
  COMPLETED: 'bg-green-600',
  FAILED: 'bg-red-600',
  CANCELLED: 'bg-yellow-600',
  TIMEOUT: 'bg-orange-600',
}

function dateToIsoStart(d?: string): string | undefined {
  return d ? new Date(`${d}T00:00:00`).toISOString() : undefined
}

function dateToIsoEnd(d?: string): string | undefined {
  return d ? new Date(`${d}T23:59:59.999`).toISOString() : undefined
}

function WorkflowsPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const search = Route.useSearch()

  const statuses = search.status ?? DEFAULT_STATUSES
  const projectIds = search.projectId ?? []
  const lastRunStatuses = search.lastRunStatus ?? []
  const sort: WorkflowSortField = search.sort ?? 'createdAt'
  const direction: SortDirection = search.dir ?? 'desc'
  const page = search.page ?? 0
  const size = search.size ?? 25
  const searchText = search.search ?? ''

  // Local controlled input for debounced name search
  const [searchInput, setSearchInput] = useState(searchText)
  useEffect(() => {
    setSearchInput(searchText)
  }, [searchText])
  useEffect(() => {
    const t = setTimeout(() => {
      if (searchInput !== searchText) {
        updateSearch({ search: searchInput || undefined, page: 0 })
      }
    }, 300)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput])

  function updateSearch(patch: Partial<WorkflowsSearch>) {
    navigate({
      to: '/workflows',
      search: (prev) => {
        const merged: WorkflowsSearch = { ...(prev as WorkflowsSearch), ...patch }
        // Strip empties
        const cleaned: WorkflowsSearch = {}
        if (merged.status && merged.status.length) cleaned.status = merged.status
        if (merged.projectId && merged.projectId.length) cleaned.projectId = merged.projectId
        if (merged.search) cleaned.search = merged.search
        if (merged.lastRunStatus && merged.lastRunStatus.length)
          cleaned.lastRunStatus = merged.lastRunStatus
        if (merged.createdFrom) cleaned.createdFrom = merged.createdFrom
        if (merged.createdTo) cleaned.createdTo = merged.createdTo
        if (merged.lastRunFrom) cleaned.lastRunFrom = merged.lastRunFrom
        if (merged.lastRunTo) cleaned.lastRunTo = merged.lastRunTo
        if (merged.sort && merged.sort !== 'createdAt') cleaned.sort = merged.sort
        if (merged.dir && merged.dir !== 'desc') cleaned.dir = merged.dir
        if (merged.page && merged.page > 0) cleaned.page = merged.page
        if (merged.size && merged.size !== 25) cleaned.size = merged.size
        return cleaned
      },
    })
  }

  const listParams: WorkflowListParams = useMemo(
    () => ({
      status: statuses,
      projectId: projectIds.length ? projectIds : undefined,
      search: searchText || undefined,
      lastRunStatus: lastRunStatuses.length ? lastRunStatuses : undefined,
      createdFrom: dateToIsoStart(search.createdFrom),
      createdTo: dateToIsoEnd(search.createdTo),
      lastRunFrom: dateToIsoStart(search.lastRunFrom),
      lastRunTo: dateToIsoEnd(search.lastRunTo),
      sort,
      direction,
      page,
      size,
    }),
    [
      statuses,
      projectIds,
      searchText,
      lastRunStatuses,
      search.createdFrom,
      search.createdTo,
      search.lastRunFrom,
      search.lastRunTo,
      sort,
      direction,
      page,
      size,
    ]
  )

  const {
    data: paged,
    isLoading: workflowsLoading,
    error,
  } = useQuery({
    queryKey: ['workflows-list', listParams],
    queryFn: () => workflowsListApi.list(listParams),
    placeholderData: (prev) => prev,
  })

  const { data: accessibleProjects } = useQuery({
    queryKey: ['workflows-accessible-projects'],
    queryFn: () => workflowsListApi.accessibleProjects(),
  })

  const [createOpen, setCreateOpen] = useState(false)
  const [editWorkflow, setEditWorkflow] = useState<Workflow | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<WorkflowListItem | null>(null)

  const invalidateList = () =>
    queryClient.invalidateQueries({ queryKey: ['workflows-list'] })

  const createMutation = useMutation({
    mutationFn: (data: CreateWorkflowRequest) =>
      workflowsApi.create(data.projectId, data),
    onSuccess: (created) => {
      invalidateList()
      setCreateOpen(false)
      navigate({
        to: '/workflows/$workflowId/edit',
        params: { workflowId: created.id },
        search: { projectId: created.projectId },
      })
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({
      projectId,
      id,
      data,
    }: {
      projectId: string
      id: string
      data: UpdateWorkflowRequest
    }) => workflowsApi.update(projectId, id, data),
    onSuccess: () => {
      invalidateList()
      setEditWorkflow(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ projectId, id }: { projectId: string; id: string }) =>
      workflowsApi.delete(projectId, id),
    onSuccess: () => {
      invalidateList()
      setDeleteTarget(null)
    },
  })

  const publishMutation = useMutation({
    mutationFn: ({ projectId, id }: { projectId: string; id: string }) =>
      workflowsApi.publish(projectId, id),
    onSuccess: invalidateList,
  })

  const archiveMutation = useMutation({
    mutationFn: ({ projectId, id }: { projectId: string; id: string }) =>
      workflowsApi.archive(projectId, id),
    onSuccess: invalidateList,
  })

  const handleSortChange = (field: WorkflowSortField, dir: SortDirection) => {
    updateSearch({ sort: field, dir, page: 0 })
  }

  const toggleStatus = (s: WorkflowStatus) => {
    const next = statuses.includes(s)
      ? statuses.filter((x) => x !== s)
      : [...statuses, s]
    updateSearch({ status: next.length ? next : undefined, page: 0 })
  }

  const toggleProject = (id: string) => {
    const next = projectIds.includes(id)
      ? projectIds.filter((x) => x !== id)
      : [...projectIds, id]
    updateSearch({ projectId: next, page: 0 })
  }

  const toggleRunStatus = (s: RequestStatus | 'NEVER') => {
    const next = lastRunStatuses.includes(s)
      ? lastRunStatuses.filter((x) => x !== s)
      : [...lastRunStatuses, s]
    updateSearch({ lastRunStatus: next, page: 0 })
  }

  const resetFilters = () => {
    navigate({ to: '/workflows', search: {} })
  }

  const projectNameMap = useMemo(() => {
    const map = new Map<string, string>()
    accessibleProjects?.forEach((p) => map.set(p.id, p.name))
    return map
  }, [accessibleProjects])

  const items = paged?.content ?? []
  const totalElements = paged?.totalElements ?? 0
  const totalPages = paged?.totalPages ?? 0

  const hasActiveFilters =
    !!searchText ||
    (search.status && search.status.length > 0) ||
    projectIds.length > 0 ||
    lastRunStatuses.length > 0 ||
    !!search.createdFrom ||
    !!search.createdTo ||
    !!search.lastRunFrom ||
    !!search.lastRunTo

  return (
    <div className="container mx-auto py-6 space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <WorkflowIcon className="h-6 w-6" />
              <CardTitle>Workflows</CardTitle>
            </div>
            <Dialog
              open={createOpen}
              onOpenChange={(open) => {
                setCreateOpen(open)
              }}
            >
              <DialogTrigger asChild>
                <Button disabled={!accessibleProjects || accessibleProjects.length === 0}>
                  <Plus className="h-4 w-4 mr-2" />
                  New Workflow
                </Button>
              </DialogTrigger>
              <DialogContent className="max-w-lg">
                <CreateWorkflowDialog
                  projects={accessibleProjects ?? []}
                  isSubmitting={createMutation.isPending}
                  onSubmit={(values) =>
                    createMutation.mutate({
                      projectId: values.projectId,
                      name: values.name,
                      description: values.description,
                      steps: [],
                      inputSchema: undefined,
                    })
                  }
                  onCancel={() => setCreateOpen(false)}
                />
              </DialogContent>
            </Dialog>
          </div>
          <CardDescription>
            View and manage workflows across all projects you have access to.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-2 mb-4">
            <div className="relative">
              <Search className="absolute left-2 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Search by name..."
                className="pl-8 w-[240px]"
              />
            </div>

            {/* Status filter */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm">
                  <Filter className="h-3.5 w-3.5 mr-1" />
                  Status
                  {statuses.length > 0 && (
                    <Badge variant="secondary" className="ml-2">
                      {statuses.length}
                    </Badge>
                  )}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Workflow status</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {ALL_STATUSES.map((s) => (
                  <DropdownMenuCheckboxItem
                    key={s}
                    checked={statuses.includes(s)}
                    onCheckedChange={() => toggleStatus(s)}
                    onSelect={(e) => e.preventDefault()}
                  >
                    {statusLabels[s]}
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>

            {/* Project filter */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm">
                  <Filter className="h-3.5 w-3.5 mr-1" />
                  Project
                  {projectIds.length > 0 && (
                    <Badge variant="secondary" className="ml-2">
                      {projectIds.length}
                    </Badge>
                  )}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent className="max-h-80 overflow-y-auto">
                <DropdownMenuLabel>Project</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {accessibleProjects && accessibleProjects.length > 0 ? (
                  accessibleProjects.map((p) => (
                    <DropdownMenuCheckboxItem
                      key={p.id}
                      checked={projectIds.includes(p.id)}
                      onCheckedChange={() => toggleProject(p.id)}
                      onSelect={(e) => e.preventDefault()}
                    >
                      {p.name}
                    </DropdownMenuCheckboxItem>
                  ))
                ) : (
                  <div className="px-2 py-1.5 text-sm text-muted-foreground">
                    No accessible projects
                  </div>
                )}
              </DropdownMenuContent>
            </DropdownMenu>

            {/* Last run status filter */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm">
                  <Filter className="h-3.5 w-3.5 mr-1" />
                  Last run
                  {lastRunStatuses.length > 0 && (
                    <Badge variant="secondary" className="ml-2">
                      {lastRunStatuses.length}
                    </Badge>
                  )}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Last run status</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {ALL_RUN_STATUSES.map((s) => (
                  <DropdownMenuCheckboxItem
                    key={s}
                    checked={lastRunStatuses.includes(s)}
                    onCheckedChange={() => toggleRunStatus(s)}
                    onSelect={(e) => e.preventDefault()}
                  >
                    {s === 'NEVER' ? 'Never run' : s}
                  </DropdownMenuCheckboxItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>

            {/* Created date range */}
            <div className="flex items-center gap-1">
              <Label className="text-xs text-muted-foreground">Created</Label>
              <Input
                type="date"
                value={search.createdFrom ?? ''}
                onChange={(e) =>
                  updateSearch({ createdFrom: e.target.value || undefined, page: 0 })
                }
                className="w-[150px]"
              />
              <span className="text-muted-foreground">–</span>
              <Input
                type="date"
                value={search.createdTo ?? ''}
                onChange={(e) =>
                  updateSearch({ createdTo: e.target.value || undefined, page: 0 })
                }
                className="w-[150px]"
              />
            </div>

            {/* Last run date range */}
            <div className="flex items-center gap-1">
              <Label className="text-xs text-muted-foreground">Last run</Label>
              <Input
                type="date"
                value={search.lastRunFrom ?? ''}
                onChange={(e) =>
                  updateSearch({ lastRunFrom: e.target.value || undefined, page: 0 })
                }
                className="w-[150px]"
              />
              <span className="text-muted-foreground">–</span>
              <Input
                type="date"
                value={search.lastRunTo ?? ''}
                onChange={(e) =>
                  updateSearch({ lastRunTo: e.target.value || undefined, page: 0 })
                }
                className="w-[150px]"
              />
            </div>

            {hasActiveFilters && (
              <Button variant="ghost" size="sm" onClick={resetFilters}>
                <X className="h-3.5 w-3.5 mr-1" />
                Reset
              </Button>
            )}
          </div>

          {/* Filter chips */}
          {hasActiveFilters && (
            <div className="flex flex-wrap items-center gap-1 mb-3">
              {search.status?.map((s) => (
                <Badge key={`s-${s}`} variant="secondary" className="gap-1">
                  Status: {statusLabels[s]}
                  <button onClick={() => toggleStatus(s)} className="ml-1">
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}
              {projectIds.map((id) => (
                <Badge key={`p-${id}`} variant="secondary" className="gap-1">
                  Project: {projectNameMap.get(id) ?? id}
                  <button onClick={() => toggleProject(id)} className="ml-1">
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}
              {lastRunStatuses.map((s) => (
                <Badge key={`lr-${s}`} variant="secondary" className="gap-1">
                  Last run: {s === 'NEVER' ? 'Never run' : s}
                  <button onClick={() => toggleRunStatus(s)} className="ml-1">
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}
              {searchText && (
                <Badge variant="secondary" className="gap-1">
                  Name: {searchText}
                  <button
                    onClick={() => updateSearch({ search: undefined, page: 0 })}
                    className="ml-1"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              )}
            </div>
          )}

          {/* Content */}
          {workflowsLoading && !paged ? (
            <div className="text-center py-8">Loading workflows...</div>
          ) : error ? (
            <div className="flex items-center justify-center py-8 text-red-600">
              <AlertCircle className="h-5 w-5 mr-2" />
              Failed to load workflows
            </div>
          ) : items.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              <WorkflowIcon className="h-12 w-12 mx-auto mb-4 opacity-50" />
              {hasActiveFilters ? (
                <>
                  <p>No workflows match the current filters</p>
                  <p className="text-sm">Try adjusting or resetting filters</p>
                </>
              ) : (
                <>
                  <p>No active workflows</p>
                  <p className="text-sm">
                    Create a workflow to get started, or adjust filters to see archived ones.
                  </p>
                </>
              )}
            </div>
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <SortableTableHeader<WorkflowSortField>
                      field="name"
                      label="Name"
                      activeField={sort}
                      direction={direction}
                      onSortChange={handleSortChange}
                    />
                    <SortableTableHeader<WorkflowSortField>
                      field="projectName"
                      label="Project"
                      activeField={sort}
                      direction={direction}
                      onSortChange={handleSortChange}
                    />
                    <SortableTableHeader<WorkflowSortField>
                      field="status"
                      label="Status"
                      activeField={sort}
                      direction={direction}
                      onSortChange={handleSortChange}
                    />
                    <TableHead>Version</TableHead>
                    <SortableTableHeader<WorkflowSortField>
                      field="createdAt"
                      label="Created"
                      activeField={sort}
                      direction={direction}
                      onSortChange={handleSortChange}
                    />
                    <SortableTableHeader<WorkflowSortField>
                      field="lastRunAt"
                      label="Last run"
                      activeField={sort}
                      direction={direction}
                      onSortChange={handleSortChange}
                    />
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((wf) => (
                    <TableRow key={wf.id}>
                      <TableCell>
                        <div>
                          <div className="font-medium">{wf.name}</div>
                          {wf.description && (
                            <div className="text-sm text-muted-foreground truncate max-w-xs">
                              {wf.description}
                            </div>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>{wf.projectName}</TableCell>
                      <TableCell>
                        <Badge className={statusColors[wf.status]}>
                          {statusLabels[wf.status]}
                        </Badge>
                      </TableCell>
                      <TableCell>v{wf.version}</TableCell>
                      <TableCell>
                        {new Date(wf.createdAt).toLocaleDateString()}
                      </TableCell>
                      <TableCell>
                        {wf.lastRunAt ? (
                          <div className="flex flex-col gap-1">
                            <span className="text-sm">
                              {new Date(wf.lastRunAt).toLocaleString()}
                            </span>
                            {wf.lastRunStatus && (
                              <Badge
                                className={`${runStatusColors[wf.lastRunStatus]} w-fit`}
                              >
                                {wf.lastRunStatus}
                              </Badge>
                            )}
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-sm">Never</span>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            title="Visual Editor"
                            onClick={() =>
                              navigate({
                                to: '/workflows/$workflowId/edit',
                                params: { workflowId: wf.id },
                                search: { projectId: wf.projectId },
                              })
                            }
                          >
                            <Settings2 className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            title="Requests"
                            onClick={() =>
                              navigate({
                                to: '/workflows/$workflowId/requests',
                                params: { workflowId: wf.id },
                                search: { projectId: wf.projectId },
                              })
                            }
                          >
                            <Eye className="h-4 w-4" />
                          </Button>
                          {wf.status === 'DRAFT' && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() =>
                                publishMutation.mutate({
                                  projectId: wf.projectId,
                                  id: wf.id,
                                })
                              }
                              title="Publish"
                            >
                              <Play className="h-4 w-4" />
                            </Button>
                          )}
                          {wf.status !== 'ARCHIVED' && (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() =>
                                archiveMutation.mutate({
                                  projectId: wf.projectId,
                                  id: wf.id,
                                })
                              }
                              title="Archive"
                            >
                              <Archive className="h-4 w-4" />
                            </Button>
                          )}
                          <Button
                            variant="outline"
                            size="sm"
                            title="Edit metadata"
                            onClick={async () => {
                              const full = await workflowsApi.get(
                                wf.projectId,
                                wf.id
                              )
                              setEditWorkflow(full)
                            }}
                          >
                            <Pencil className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setDeleteTarget(wf)}
                            title="Delete"
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              {/* Pagination footer */}
              <div className="flex items-center justify-between mt-4 text-sm">
                <div className="text-muted-foreground">
                  {totalElements === 0
                    ? 'No results'
                    : `Showing ${page * size + 1}–${Math.min(
                        (page + 1) * size,
                        totalElements
                      )} of ${totalElements}`}
                </div>
                <div className="flex items-center gap-2">
                  <Label className="text-xs text-muted-foreground">Rows</Label>
                  <Select
                    value={String(size)}
                    onValueChange={(v) =>
                      updateSearch({ size: Number(v), page: 0 })
                    }
                  >
                    <SelectTrigger className="w-[80px] h-8">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {PAGE_SIZES.map((n) => (
                        <SelectItem key={n} value={String(n)}>
                          {n}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page <= 0}
                    onClick={() => updateSearch({ page: page - 1 })}
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <span className="px-2">
                    Page {page + 1} of {Math.max(totalPages, 1)}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page + 1 >= totalPages}
                    onClick={() => updateSearch({ page: page + 1 })}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* Edit metadata dialog */}
      <Dialog
        open={!!editWorkflow}
        onOpenChange={(open) => {
          if (!open) setEditWorkflow(null)
        }}
      >
        <DialogContent className="max-w-lg">
          {editWorkflow && (
            <EditWorkflowDialog
              workflow={editWorkflow}
              isSubmitting={updateMutation.isPending}
              onSubmit={(values, status) =>
                updateMutation.mutate({
                  projectId: editWorkflow.projectId,
                  id: editWorkflow.id,
                  data: {
                    name: values.name,
                    description: values.description,
                    steps: editWorkflow.steps,
                    inputSchema: editWorkflow.inputSchema ?? undefined,
                    status,
                  } satisfies UpdateWorkflowRequest,
                })
              }
              onCancel={() => setEditWorkflow(null)}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* Delete confirmation */}
      <AlertDialog
        open={!!deleteTarget}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete workflow</AlertDialogTitle>
            <AlertDialogDescription>
              Delete workflow{' '}
              <span className="font-medium">"{deleteTarget?.name}"</span>? This
              also removes its runs and cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>
              Cancel
            </AlertDialogCancel>
            <AlertDialogAction
              disabled={deleteMutation.isPending}
              onClick={(e) => {
                e.preventDefault()
                if (!deleteTarget) return
                deleteMutation.mutate({
                  projectId: deleteTarget.projectId,
                  id: deleteTarget.id,
                })
              }}
              className="bg-red-600 hover:bg-red-700 text-white"
            >
              {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Dialog components
// ---------------------------------------------------------------------------

interface CreateWorkflowDialogProps {
  projects: { id: string; name: string }[]
  isSubmitting: boolean
  onSubmit: (values: WorkflowMetadataFormValues) => void
  onCancel: () => void
}

function CreateWorkflowDialog({
  projects,
  isSubmitting,
  onSubmit,
  onCancel,
}: CreateWorkflowDialogProps) {
  const defaultProjectId = projects.length === 1 ? projects[0].id : ''
  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<WorkflowMetadataFormValues>({
    resolver: zodResolver(workflowMetadataSchema),
    defaultValues: {
      projectId: defaultProjectId,
      name: '',
      description: '',
    },
  })

  const projectId = watch('projectId')

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <DialogHeader>
        <DialogTitle>Create workflow</DialogTitle>
        <DialogDescription>
          Provide a name and project. You'll define steps in the editor.
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 py-4">
        <div className="grid gap-2">
          <Label htmlFor="create-project">Project *</Label>
          <Select
            value={projectId}
            onValueChange={(v) =>
              setValue('projectId', v, {
                shouldValidate: true,
                shouldDirty: true,
              })
            }
          >
            <SelectTrigger id="create-project">
              <SelectValue placeholder="Select a project..." />
            </SelectTrigger>
            <SelectContent>
              {projects.map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {errors.projectId && (
            <p className="text-xs text-red-600">{errors.projectId.message}</p>
          )}
        </div>

        <div className="grid gap-2">
          <Label htmlFor="create-name">Name *</Label>
          <Input
            id="create-name"
            placeholder="e.g., Code Review Pipeline"
            {...register('name')}
          />
          {errors.name && (
            <p className="text-xs text-red-600">{errors.name.message}</p>
          )}
        </div>

        <div className="grid gap-2">
          <Label htmlFor="create-description">Description</Label>
          <Textarea
            id="create-description"
            rows={3}
            placeholder="What does this workflow do?"
            {...register('description')}
          />
          {errors.description && (
            <p className="text-xs text-red-600">
              {errors.description.message}
            </p>
          )}
        </div>
      </div>

      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          onClick={onCancel}
          disabled={isSubmitting}
        >
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Creating...' : 'Create & open editor'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditWorkflowDialogProps {
  workflow: Workflow
  isSubmitting: boolean
  onSubmit: (
    values: WorkflowMetadataFormValues,
    status: WorkflowStatus
  ) => void
  onCancel: () => void
}

function EditWorkflowDialog({
  workflow,
  isSubmitting,
  onSubmit,
  onCancel,
}: EditWorkflowDialogProps) {
  const [status, setStatus] = useState<WorkflowStatus>(workflow.status)
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<WorkflowMetadataFormValues>({
    resolver: zodResolver(workflowMetadataSchema),
    defaultValues: {
      projectId: workflow.projectId,
      name: workflow.name,
      description: workflow.description ?? '',
    },
  })

  return (
    <form onSubmit={handleSubmit((values) => onSubmit(values, status))}>
      <DialogHeader>
        <DialogTitle>Edit workflow</DialogTitle>
        <DialogDescription>
          Update workflow metadata. Steps and inputs are edited from the visual
          editor.
        </DialogDescription>
      </DialogHeader>

      <div className="grid gap-4 py-4">
        <input type="hidden" {...register('projectId')} />

        <div className="grid gap-2">
          <Label htmlFor="edit-name">Name *</Label>
          <Input id="edit-name" {...register('name')} />
          {errors.name && (
            <p className="text-xs text-red-600">{errors.name.message}</p>
          )}
        </div>

        <div className="grid gap-2">
          <Label htmlFor="edit-description">Description</Label>
          <Textarea
            id="edit-description"
            rows={3}
            {...register('description')}
          />
          {errors.description && (
            <p className="text-xs text-red-600">
              {errors.description.message}
            </p>
          )}
        </div>

        <div className="grid gap-2">
          <Label htmlFor="edit-status">Status</Label>
          <Select
            value={status}
            onValueChange={(v) => setStatus(v as WorkflowStatus)}
          >
            <SelectTrigger id="edit-status">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="DRAFT">Draft</SelectItem>
              <SelectItem value="PUBLISHED">Published</SelectItem>
              <SelectItem value="DISABLED">Disabled</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            Archived workflows can only be restored to Disabled via the API.
          </p>
        </div>
      </div>

      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          onClick={onCancel}
          disabled={isSubmitting}
        >
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Saving...' : 'Save changes'}
        </Button>
      </DialogFooter>
    </form>
  )
}
