import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { auditLogApi, type AuditLogEntry, type AuditLogQuery } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ScrollText, Search, ChevronLeft, ChevronRight, FileJson } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/admin/audit-log')({
  component: AuditLogPage,
})

const PAGE_SIZE = 50

function AuditLogPage() {
  // Filter state lives in two pieces: the form (mutable, user is typing) and
  // the applied query (only changes on Apply / Reset / Pagination so we
  // don't refetch on every keystroke).
  const [form, setForm] = useState<AuditLogQuery>({})
  const [query, setQuery] = useState<AuditLogQuery>({ page: 0, size: PAGE_SIZE })
  const [detail, setDetail] = useState<AuditLogEntry | null>(null)

  const { data, isLoading, error, isFetching } = useQuery({
    queryKey: ['audit-log', query],
    queryFn: () => auditLogApi.search(query),
  })

  const apply = () => {
    // ISO-friendly: if the user typed a datetime-local value we leave it as
    // is — Spring's @DateTimeFormat.ISO.DATE_TIME handles the seconds-less
    // variant when paired with a trailing 'Z' on the server side.
    setQuery({ ...form, page: 0, size: PAGE_SIZE })
  }

  const reset = () => {
    setForm({})
    setQuery({ page: 0, size: PAGE_SIZE })
  }

  const goPage = (page: number) => {
    setQuery((q) => ({ ...q, page }))
  }

  const items = data?.items ?? []
  const total = data?.totalElements ?? 0
  const currentPage = query.page ?? 0
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold flex items-center gap-2">
            <ScrollText className="h-7 w-7" />
            Audit Log
          </h1>
          <p className="text-muted-foreground">
            Privileged actions across the platform. Append-only, PLATFORM_ADMIN only.
          </p>
        </div>
      </div>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="text-base">Filters</CardTitle>
          <CardDescription>
            All filters AND together. Leave blank to widen.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <Label htmlFor="action">Action</Label>
              <Input
                id="action"
                placeholder="LOGIN, USER_ROLE_GRANTED, QUOTA_UPDATED…"
                value={form.action ?? ''}
                onChange={(e) => setForm({ ...form, action: e.target.value || undefined })}
              />
            </div>
            <div>
              <Label htmlFor="resourceType">Resource type</Label>
              <Input
                id="resourceType"
                placeholder="User, Secret, Quota…"
                value={form.resourceType ?? ''}
                onChange={(e) =>
                  setForm({ ...form, resourceType: e.target.value || undefined })
                }
              />
            </div>
            <div>
              <Label htmlFor="actorUserId">Actor user ID</Label>
              <Input
                id="actorUserId"
                placeholder="UUID"
                value={form.actorUserId ?? ''}
                onChange={(e) =>
                  setForm({ ...form, actorUserId: e.target.value || undefined })
                }
              />
            </div>
            <div>
              <Label htmlFor="resourceId">Resource ID</Label>
              <Input
                id="resourceId"
                placeholder="UUID"
                value={form.resourceId ?? ''}
                onChange={(e) =>
                  setForm({ ...form, resourceId: e.target.value || undefined })
                }
              />
            </div>
            <div>
              <Label htmlFor="since">Since (ISO datetime)</Label>
              <Input
                id="since"
                type="datetime-local"
                value={form.since ?? ''}
                onChange={(e) => setForm({ ...form, since: e.target.value || undefined })}
              />
            </div>
            <div>
              <Label htmlFor="until">Until (ISO datetime)</Label>
              <Input
                id="until"
                type="datetime-local"
                value={form.until ?? ''}
                onChange={(e) => setForm({ ...form, until: e.target.value || undefined })}
              />
            </div>
          </div>
          <div className="flex gap-2 mt-4">
            <Button onClick={apply} disabled={isFetching}>
              <Search className="h-4 w-4 mr-2" />
              Apply
            </Button>
            <Button variant="outline" onClick={reset} disabled={isFetching}>
              Reset
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">
            Entries{' '}
            <span className="text-muted-foreground font-normal">
              ({total} total, page {currentPage + 1} of {totalPages})
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="text-muted-foreground py-8 text-center">Loading…</div>
          ) : error ? (
            <div className="text-destructive py-8 text-center">
              Failed to load audit log
            </div>
          ) : items.length === 0 ? (
            <div className="text-muted-foreground py-8 text-center">
              No entries match these filters.
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[180px]">Timestamp</TableHead>
                  <TableHead className="w-[160px]">Action</TableHead>
                  <TableHead className="w-[120px]">Resource</TableHead>
                  <TableHead className="w-[260px]">Actor</TableHead>
                  <TableHead>IP</TableHead>
                  <TableHead className="w-[80px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="font-mono text-xs">
                      {formatTimestamp(e.createdAt)}
                    </TableCell>
                    <TableCell className="font-medium">{e.action}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {e.resourceType ?? '—'}
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {e.actorUserId ?? <span className="italic">SYSTEM</span>}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {e.ipAddress ?? '—'}
                    </TableCell>
                    <TableCell>
                      {e.payloadJson && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setDetail(e)}
                          title="View payload"
                        >
                          <FileJson className="h-4 w-4" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          <div className="flex items-center justify-between mt-4">
            <div className="text-sm text-muted-foreground">
              Showing {items.length === 0 ? 0 : currentPage * PAGE_SIZE + 1}–
              {currentPage * PAGE_SIZE + items.length} of {total}
            </div>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={currentPage <= 0 || isFetching}
                onClick={() => goPage(currentPage - 1)}
              >
                <ChevronLeft className="h-4 w-4 mr-1" />
                Prev
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={currentPage >= totalPages - 1 || isFetching}
                onClick={() => goPage(currentPage + 1)}
              >
                Next
                <ChevronRight className="h-4 w-4 ml-1" />
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm">
              {detail?.action} · {detail && formatTimestamp(detail.createdAt)}
            </DialogTitle>
            <DialogDescription>
              {detail?.resourceType && (
                <span>
                  Resource: {detail.resourceType}{' '}
                  {detail.resourceId && (
                    <span className="font-mono">({detail.resourceId})</span>
                  )}
                </span>
              )}
            </DialogDescription>
          </DialogHeader>
          {detail && (
            <div className="space-y-3 text-sm">
              <Field label="Actor" value={detail.actorUserId ?? 'SYSTEM'} />
              <Field label="Scope" value={describeScope(detail)} />
              <Field label="IP" value={detail.ipAddress ?? '—'} />
              <Field label="User agent" value={detail.userAgent ?? '—'} mono />
              <Field label="Request id" value={detail.requestId ?? '—'} mono />
              <div>
                <Label className="text-xs">Payload</Label>
                <pre className="mt-1 bg-muted rounded p-3 overflow-x-auto text-xs">
                  {prettyJson(detail.payloadJson)}
                </pre>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Field({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2">
      <Label className="text-xs w-24 shrink-0">{label}</Label>
      <span className={mono ? 'font-mono text-xs break-all' : 'text-sm'}>{value}</span>
    </div>
  )
}

function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toISOString().replace('T', ' ').replace('Z', '')
  } catch {
    return iso
  }
}

function describeScope(e: AuditLogEntry): string {
  if (!e.scopeType) return '—'
  return `${e.scopeType}${e.scopeId ? ` (${e.scopeId})` : ''}`
}

function prettyJson(s: string | null): string {
  if (!s) return ''
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}
