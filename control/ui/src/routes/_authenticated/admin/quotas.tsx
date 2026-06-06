import { createFileRoute } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  quotasApi,
  type Quota,
  type QuotaScope,
  type QuotaResourceType,
  type QuotaPeriod,
  type CreateQuotaRequest,
  type UpdateQuotaRequest,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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
import { Gauge, Plus, Pencil, Trash2 } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/admin/quotas')({
  component: QuotasAdminPage,
})

const SCOPES: QuotaScope[] = ['ORG', 'GROUP', 'PROJECT', 'USER']
const RESOURCES: QuotaResourceType[] = ['TOKENS', 'COST_USD_CENTS']
const PERIODS: QuotaPeriod[] = ['DAILY', 'MONTHLY_CALENDAR', 'LIFETIME']

function QuotasAdminPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [editQuota, setEditQuota] = useState<Quota | null>(null)

  const {
    data: quotas,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['quotas'],
    queryFn: () => quotasApi.list(),
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['quotas'] })
  }

  const createMutation = useMutation({
    mutationFn: (data: CreateQuotaRequest) => quotasApi.create(data),
    onSuccess: () => {
      invalidate()
      setCreateOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateQuotaRequest }) =>
      quotasApi.update(id, data),
    onSuccess: () => {
      invalidate()
      setEditQuota(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => quotasApi.delete(id),
    onSuccess: invalidate,
  })

  if (isLoading) {
    return (
      <div className="p-8">
        <div className="text-muted-foreground">Loading quotas...</div>
      </div>
    )
  }
  if (error) {
    return (
      <div className="p-8">
        <div className="text-destructive">Failed to load quotas</div>
      </div>
    )
  }

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-2">
            <Gauge className="h-7 w-7" />
            Quotas
          </h1>
          <p className="text-muted-foreground">
            Token and cost ceilings per scope. Child-tightens-only: a
            project quota may not exceed its group; a group may not
            exceed its org.
          </p>
        </div>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle>All Quotas</CardTitle>
            <CardDescription>
              {quotas?.length ?? 0} quota(s) configured. Warnings fire
              at 80% consumption; hard-stop at 100% with HTTP 429.
            </CardDescription>
          </div>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger asChild>
              <Button variant="outline">
                <Plus className="h-4 w-4 mr-2" />
                New Quota
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-xl">
              <CreateQuotaForm
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
                <TableHead>Scope</TableHead>
                <TableHead>Resource</TableHead>
                <TableHead>Period</TableHead>
                <TableHead className="text-right">Limit</TableHead>
                <TableHead>Enforced</TableHead>
                <TableHead>Updated</TableHead>
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {quotas?.map((q) => (
                <TableRow key={q.id}>
                  <TableCell>
                    <div className="font-medium">{q.scopeType}</div>
                    <div className="text-xs text-muted-foreground font-mono">
                      {q.scopeId}
                    </div>
                  </TableCell>
                  <TableCell>{q.resourceType}</TableCell>
                  <TableCell>{q.period}</TableCell>
                  <TableCell className="text-right font-mono">
                    {q.limitAmount.toLocaleString()}
                  </TableCell>
                  <TableCell>
                    {q.enforced ? (
                      <span className="text-xs px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-700">
                        enforced
                      </span>
                    ) : (
                      <span className="text-xs px-2 py-0.5 rounded bg-amber-500/10 text-amber-700">
                        warn-only
                      </span>
                    )}
                  </TableCell>
                  <TableCell className="text-xs">
                    {new Date(q.updatedAt).toLocaleString()}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setEditQuota(q)}
                        title="Edit"
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          if (
                            window.confirm(
                              `Delete the ${q.scopeType} ${q.resourceType}/${q.period} quota?`,
                            )
                          ) {
                            deleteMutation.mutate(q.id)
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
              {(!quotas || quotas.length === 0) && (
                <TableRow>
                  <TableCell
                    colSpan={7}
                    className="text-center text-muted-foreground py-8"
                  >
                    No quotas configured yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog
        open={!!editQuota}
        onOpenChange={(open) => !open && setEditQuota(null)}
      >
        <DialogContent className="max-w-xl">
          {editQuota && (
            <EditQuotaForm
              quota={editQuota}
              onSubmit={(data) =>
                updateMutation.mutate({ id: editQuota.id, data })
              }
              isLoading={updateMutation.isPending}
              error={updateMutation.error?.message}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

interface CreateQuotaFormProps {
  onSubmit: (data: CreateQuotaRequest) => void
  isLoading: boolean
  error?: string
}

function CreateQuotaForm({ onSubmit, isLoading, error }: CreateQuotaFormProps) {
  const [scopeType, setScopeType] = useState<QuotaScope>('PROJECT')
  const [scopeId, setScopeId] = useState('')
  const [resourceType, setResourceType] = useState<QuotaResourceType>('TOKENS')
  const [period, setPeriod] = useState<QuotaPeriod>('DAILY')
  const [limitAmount, setLimitAmount] = useState('100000')
  const [enforced, setEnforced] = useState(true)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      scopeType,
      scopeId: scopeId.trim(),
      resourceType,
      period,
      limitAmount: Number.parseInt(limitAmount, 10),
      enforced,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Quota</DialogTitle>
        <DialogDescription>
          Apply to a specific scope. A project quota cannot exceed its
          group; a group cannot exceed its org.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
            {error}
          </div>
        )}

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-2">
            <Label htmlFor="scope-type">Scope</Label>
            <Select
              value={scopeType}
              onValueChange={(v) => setScopeType(v as QuotaScope)}
            >
              <SelectTrigger id="scope-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {SCOPES.map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="scope-id">Scope ID</Label>
            <Input
              id="scope-id"
              value={scopeId}
              onChange={(e) => setScopeId(e.target.value)}
              placeholder="UUID"
              required
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-2">
            <Label htmlFor="resource-type">Resource</Label>
            <Select
              value={resourceType}
              onValueChange={(v) => setResourceType(v as QuotaResourceType)}
            >
              <SelectTrigger id="resource-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {RESOURCES.map((r) => (
                  <SelectItem key={r} value={r}>
                    {r}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="period">Period</Label>
            <Select
              value={period}
              onValueChange={(v) => setPeriod(v as QuotaPeriod)}
            >
              <SelectTrigger id="period">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PERIODS.map((p) => (
                  <SelectItem key={p} value={p}>
                    {p}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="limit">Limit</Label>
          <Input
            id="limit"
            type="number"
            min={0}
            value={limitAmount}
            onChange={(e) => setLimitAmount(e.target.value)}
            required
          />
          <p className="text-xs text-muted-foreground">
            {resourceType === 'TOKENS'
              ? 'Tokens (input + output combined).'
              : 'US cents (100 = $1.00).'}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <input
            id="enforced"
            type="checkbox"
            checked={enforced}
            onChange={(e) => setEnforced(e.target.checked)}
          />
          <Label htmlFor="enforced" className="cursor-pointer">
            Enforce (uncheck for warn-only telemetry mode)
          </Label>
        </div>
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Creating...' : 'Create'}
        </Button>
      </DialogFooter>
    </form>
  )
}

interface EditQuotaFormProps {
  quota: Quota
  onSubmit: (data: UpdateQuotaRequest) => void
  isLoading: boolean
  error?: string
}

function EditQuotaForm({
  quota,
  onSubmit,
  isLoading,
  error,
}: EditQuotaFormProps) {
  const [limitAmount, setLimitAmount] = useState(String(quota.limitAmount))
  const [enforced, setEnforced] = useState(quota.enforced)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      limitAmount: Number.parseInt(limitAmount, 10),
      enforced,
      tags: quota.tags,
    })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Edit Quota</DialogTitle>
        <DialogDescription>
          {quota.scopeType} &middot; {quota.resourceType} &middot;{' '}
          {quota.period}
          <br />
          <span className="font-mono text-xs">{quota.scopeId}</span>
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">
            {error}
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="edit-limit">Limit</Label>
          <Input
            id="edit-limit"
            type="number"
            min={0}
            value={limitAmount}
            onChange={(e) => setLimitAmount(e.target.value)}
            required
          />
        </div>
        <div className="flex items-center gap-2">
          <input
            id="edit-enforced"
            type="checkbox"
            checked={enforced}
            onChange={(e) => setEnforced(e.target.checked)}
          />
          <Label htmlFor="edit-enforced" className="cursor-pointer">
            Enforce (uncheck for warn-only telemetry mode)
          </Label>
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
