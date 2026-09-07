// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import {
  myWorkApi,
  projectsApi,
  conversationsApi,
  approvalsApi,
  type MyApprovalRow,
  type MyArchivedRow,
  type MyAssistantRow,
  type MyWorkflowRow,
  type Project,
} from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@/components/ui/tabs'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Workflow as WorkflowIcon,
  MessageSquare,
  CheckSquare,
  Archive,
  Sparkles,
  Check,
  X,
  ExternalLink,
} from 'lucide-react'

/** Render an ISO timestamp as a short local date-time, or an em-dash when absent. */
function formatWhen(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

export function MyWorkPage() {
  const [selectedProjectIds, setSelectedProjectIds] = useState<string[]>([])
  const [tab, setTab] = useState('workflows')

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => projectsApi.list(),
  })

  const scope = selectedProjectIds.length > 0 ? selectedProjectIds : undefined

  const { data: summary } = useQuery({
    queryKey: ['my-work-summary', scope],
    queryFn: () => myWorkApi.summary(scope),
  })

  const toggleProject = (id: string) =>
    setSelectedProjectIds((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id],
    )

  return (
    <div className="p-8 space-y-6">
      <div>
        <h1 className="text-3xl font-bold">My Work</h1>
        <p className="text-muted-foreground">
          Everything assigned to you across projects — in one place.
        </p>
      </div>

      <ProjectFilter
        projects={projects ?? []}
        selected={selectedProjectIds}
        onToggle={toggleProject}
        onClear={() => setSelectedProjectIds([])}
      />

      {summary?.firstRun && (
        <Card className="border-dashed">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Sparkles className="h-5 w-5 text-primary" />
              Get started
            </CardTitle>
            <CardDescription>
              You don't have any workflows or assistants yet. Create your first
              service to see it here.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex gap-2">
            {summary.canCreateWorkflow && (
              <Button asChild variant="outline">
                <a href="/workflows">
                  <WorkflowIcon className="h-4 w-4 mr-2" />
                  New workflow
                </a>
              </Button>
            )}
            {summary.canCreateConversation && (
              <Button asChild variant="outline">
                <a href="/assistants">
                  <MessageSquare className="h-4 w-4 mr-2" />
                  New assistant
                </a>
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="workflows">
            <WorkflowIcon className="h-4 w-4 mr-2" />
            Workflows
            <CounterBadge value={summary?.workflows.created} />
          </TabsTrigger>
          <TabsTrigger value="conversations">
            <MessageSquare className="h-4 w-4 mr-2" />
            Conversations
            <CounterBadge value={summary?.conversations.created} />
          </TabsTrigger>
          <TabsTrigger value="approvals">
            <CheckSquare className="h-4 w-4 mr-2" />
            Approvals
            <CounterBadge value={summary?.approvalsPending} />
          </TabsTrigger>
          <TabsTrigger value="archived">
            <Archive className="h-4 w-4 mr-2" />
            Archived
            <CounterBadge value={summary?.archivedCount} />
          </TabsTrigger>
        </TabsList>

        <TabsContent value="workflows" className="mt-4">
          <WorkflowsTab scope={scope} />
        </TabsContent>
        <TabsContent value="conversations" className="mt-4">
          <ConversationsTab scope={scope} />
        </TabsContent>
        <TabsContent value="approvals" className="mt-4">
          <ApprovalsTab scope={scope} />
        </TabsContent>
        <TabsContent value="archived" className="mt-4">
          <ArchivedTab scope={scope} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function CounterBadge({ value }: { value: number | undefined }) {
  if (value == null || value === 0) return null
  return (
    <span className="ml-2 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
      {value}
    </span>
  )
}

function ProjectFilter({
  projects,
  selected,
  onToggle,
  onClear,
}: {
  projects: Project[]
  selected: string[]
  onToggle: (id: string) => void
  onClear: () => void
}) {
  const sorted = useMemo(
    () => [...projects].sort((a, b) => a.name.localeCompare(b.name)),
    [projects],
  )
  if (sorted.length === 0) return null
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-sm text-muted-foreground mr-1">Projects:</span>
      <Button
        size="sm"
        variant={selected.length === 0 ? 'default' : 'outline'}
        onClick={onClear}
      >
        All projects
      </Button>
      {sorted.map((p) => (
        <Button
          key={p.id}
          size="sm"
          variant={selected.includes(p.id) ? 'default' : 'outline'}
          onClick={() => onToggle(p.id)}
        >
          {selected.includes(p.id) && <Check className="h-3.5 w-3.5 mr-1" />}
          {p.name}
        </Button>
      ))}
    </div>
  )
}

function EmptyRow({ colSpan, label }: { colSpan: number; label: string }) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} className="text-center text-muted-foreground py-8">
        {label}
      </TableCell>
    </TableRow>
  )
}

function WorkflowsTab({ scope }: { scope: string[] | undefined }) {
  const navigate = useNavigate()
  const { data, isLoading } = useQuery({
    queryKey: ['my-work-workflows', scope],
    queryFn: () => myWorkApi.workflows({ projectIds: scope }),
  })
  const rows = data ?? []
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Project</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Active runs</TableHead>
          <TableHead>Last run</TableHead>
          <TableHead className="w-0" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {isLoading ? (
          <EmptyRow colSpan={6} label="Loading…" />
        ) : rows.length === 0 ? (
          <EmptyRow colSpan={6} label="No workflows in scope." />
        ) : (
          rows.map((r: MyWorkflowRow) => (
            <TableRow key={r.id}>
              <TableCell className="font-medium">{r.name}</TableCell>
              <TableCell>{r.projectName ?? '—'}</TableCell>
              <TableCell>
                <Badge variant="outline">{r.status}</Badge>
              </TableCell>
              <TableCell>{r.activeExecutions}</TableCell>
              <TableCell className="text-muted-foreground">
                {formatWhen(r.lastExecutionAt)}
              </TableCell>
              <TableCell>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => navigate({ to: '/workflows' })}
                >
                  Open
                </Button>
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  )
}

function ConversationsTab({ scope }: { scope: string[] | undefined }) {
  const navigate = useNavigate()
  const { data: continueRail } = useQuery({
    queryKey: ['my-work-continue', scope],
    queryFn: () => myWorkApi.continueRail({ projectIds: scope, limit: 5 }),
  })
  const { data, isLoading } = useQuery({
    queryKey: ['my-work-conversations', scope],
    queryFn: () => myWorkApi.conversations({ projectIds: scope }),
  })
  const rows = data ?? []
  return (
    <div className="space-y-4">
      {continueRail && continueRail.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm text-muted-foreground mr-1">Continue:</span>
          {continueRail.map((c) => (
            <Badge key={c.conversationId} variant="secondary">
              {c.title || c.assistantName || 'Untitled session'}
            </Badge>
          ))}
        </div>
      )}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Assistant</TableHead>
            <TableHead>Project</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Active sessions</TableHead>
            <TableHead>Last activity</TableHead>
            <TableHead className="w-0" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <EmptyRow colSpan={6} label="Loading…" />
          ) : rows.length === 0 ? (
            <EmptyRow colSpan={6} label="No assistants in scope." />
          ) : (
            rows.map((r: MyAssistantRow) => (
              <TableRow key={r.id}>
                <TableCell className="font-medium">{r.name}</TableCell>
                <TableCell>{r.projectName ?? '—'}</TableCell>
                <TableCell>
                  <Badge variant="outline">{r.status}</Badge>
                </TableCell>
                <TableCell>{r.activeSessions}</TableCell>
                <TableCell className="text-muted-foreground">
                  {formatWhen(r.lastSessionAt)}
                </TableCell>
                <TableCell>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      navigate({
                        to: '/projects/$projectId/chat',
                        params: { projectId: r.projectId },
                      })
                    }
                  >
                    Open
                  </Button>
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  )
}

function ApprovalsTab({ scope }: { scope: string[] | undefined }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [rejectWithCommentRow, setRejectWithCommentRow] = useState<MyApprovalRow | null>(null)
  const [rejectComment, setRejectComment] = useState('')
  const { data, isLoading } = useQuery({
    queryKey: ['my-work-approvals', scope],
    queryFn: () => myWorkApi.approvals({ projectIds: scope }),
  })

  const decide = useMutation({
    // §17.4: route by source — a CONVERSATION approval posts to the
    // conversation's decision endpoint; an EXECUTION approval (workflow
    // orchestration / ORCH_REVIEW task) posts to the unified approvals
    // endpoint, where the engine's decide path applies the §16.6 tuple
    // under the triggering-user gate. The endpoints return different
    // bodies — only the invalidated queries matter afterwards.
    mutationFn: async ({
      row,
      decision,
      comment,
    }: {
      row: MyApprovalRow
      decision: 'APPROVED' | 'REJECTED'
      comment?: string
    }): Promise<void> => {
      if (row.source === 'EXECUTION') {
        await approvalsApi.decide(row.messageId, decision, comment)
        return
      }
      await conversationsApi.submitApprovalDecision(
        row.conversationId!,
        row.messageId,
        decision,
        comment,
      )
    },
    onSuccess: () => {
      setRejectWithCommentRow(null)
      setRejectComment('')
      queryClient.invalidateQueries({ queryKey: ['my-work-approvals'] })
      queryClient.invalidateQueries({ queryKey: ['my-work-summary'] })
    },
  })

  const rows = data ?? []
  return (
    <>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Request</TableHead>
            <TableHead>Source</TableHead>
            <TableHead>Project</TableHead>
            <TableHead>Requested</TableHead>
            <TableHead>Expires</TableHead>
            <TableHead className="w-0" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading ? (
            <EmptyRow colSpan={6} label="Loading…" />
          ) : rows.length === 0 ? (
            <EmptyRow colSpan={6} label="No pending approvals." />
          ) : (
            rows.map((r: MyApprovalRow) => (
              <TableRow key={r.messageId}>
                <TableCell className="max-w-md truncate font-medium">
                  {r.summary || 'Approval requested'}
                </TableCell>
                <TableCell>
                  <Badge variant="outline">
                    {r.source === 'CONVERSATION' ? 'Conversation' : 'Execution'}
                  </Badge>
                </TableCell>
                <TableCell>{r.projectName ?? '—'}</TableCell>
                <TableCell className="text-muted-foreground">
                  {formatWhen(r.requestedAt)}
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {formatWhen(r.expiresAt)}
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-1">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ row: r, decision: 'APPROVED' })}
                    >
                      <Check className="h-4 w-4 mr-1" />
                      Approve
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ row: r, decision: 'REJECTED' })}
                    >
                      <X className="h-4 w-4 mr-1" />
                      Reject
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={decide.isPending}
                      onClick={() => {
                        setRejectWithCommentRow(r)
                        setRejectComment('')
                      }}
                    >
                      Reject with comment
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        r.source === 'CONVERSATION'
                          ? navigate({
                              to: '/projects/$projectId/chat',
                              params: { projectId: r.projectId },
                            })
                          : navigate({
                              to: '/workflows',
                              search: { projectId: [r.projectId] },
                            })
                      }
                      title={
                        r.source === 'CONVERSATION'
                          ? 'Open conversation'
                          : 'Open the project workflows'
                      }
                    >
                      <ExternalLink className="h-4 w-4" />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      <Dialog
        open={!!rejectWithCommentRow}
        onOpenChange={(open) => {
          if (!open) {
            setRejectWithCommentRow(null)
            setRejectComment('')
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Reject with comment</DialogTitle>
            <DialogDescription>
              Add feedback for the agent before rejecting this request.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <p className="text-sm text-muted-foreground">
              {rejectWithCommentRow?.summary || 'Approval requested'}
            </p>
            <Textarea
              value={rejectComment}
              onChange={(e) => setRejectComment(e.target.value)}
              placeholder="Reason for rejection"
              className="min-h-[84px]"
            />
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setRejectWithCommentRow(null)
                setRejectComment('')
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              disabled={decide.isPending || !rejectWithCommentRow || !rejectComment.trim()}
              onClick={() => {
                if (!rejectWithCommentRow) return
                decide.mutate({
                  row: rejectWithCommentRow,
                  decision: 'REJECTED',
                  comment: rejectComment.trim(),
                })
              }}
            >
              Reject with comment
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function ArchivedTab({ scope }: { scope: string[] | undefined }) {
  const { data, isLoading } = useQuery({
    queryKey: ['my-work-archived', scope],
    queryFn: () => myWorkApi.archived({ projectIds: scope }),
  })
  const rows = data ?? []
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Type</TableHead>
          <TableHead>Project</TableHead>
          <TableHead>Archived</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {isLoading ? (
          <EmptyRow colSpan={4} label="Loading…" />
        ) : rows.length === 0 ? (
          <EmptyRow colSpan={4} label="Nothing archived." />
        ) : (
          rows.map((r: MyArchivedRow) => (
            <TableRow key={`${r.type}-${r.id}`}>
              <TableCell className="font-medium">{r.name}</TableCell>
              <TableCell>
                <Badge variant="outline">
                  {r.type === 'WORKFLOW' ? 'Workflow' : 'Assistant'}
                </Badge>
              </TableCell>
              <TableCell>{r.projectName ?? '—'}</TableCell>
              <TableCell className="text-muted-foreground">
                {formatWhen(r.archivedAt)}
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  )
}