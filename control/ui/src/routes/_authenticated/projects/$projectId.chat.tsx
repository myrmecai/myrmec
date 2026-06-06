import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  agentsApi,
  conversationsApi,
  projectsApi,
  type Agent,
  type Conversation,
  type ConversationMessage,
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
  DialogTrigger,
} from '@/components/ui/dialog'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { ChevronLeft, MessageSquare, Plus, Send, User, Bot } from 'lucide-react'

export const Route = createFileRoute('/_authenticated/projects/$projectId/chat')({
  component: ProjectChatPage,
})

const ACCESS_TOKEN_KEY = 'myrmec_access_token'

/**
 * Per-conversation streaming buffer. Indexed by {@code sequenceNo} so a
 * late-arriving viewer that joins mid-stream still reassembles correctly
 * when the broker replays buffered deltas.
 */
type StreamingMessage = {
  sequenceNo: number
  content: string
  complete: boolean
}

function ProjectChatPage() {
  const { projectId } = Route.useParams()
  const queryClient = useQueryClient()

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)

  const { data: project } = useQuery({
    queryKey: ['projects', projectId],
    queryFn: () => projectsApi.get(projectId),
  })

  const { data: conversations, isLoading: convLoading } = useQuery({
    queryKey: ['conversations', projectId],
    queryFn: () => conversationsApi.listByProject(projectId),
  })

  // Auto-select the most recent conversation on first load.
  useEffect(() => {
    if (!selectedId && conversations && conversations.length > 0) {
      setSelectedId(conversations[0].id)
    }
  }, [conversations, selectedId])

  const selected = useMemo(
    () => conversations?.find((c) => c.id === selectedId) ?? null,
    [conversations, selectedId],
  )

  return (
    <div className="flex h-[calc(100vh-3.5rem)]">
      {/* Sidebar */}
      <aside
        className="w-72 border-r bg-card flex flex-col"
        data-testid="conversation-sidebar"
      >
        <div className="p-4 border-b">
          <Link
            to="/projects"
            className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground mb-3"
          >
            <ChevronLeft className="h-4 w-4 mr-1" />
            Projects
          </Link>
          <h2 className="font-semibold text-lg">{project?.name ?? 'Chat'}</h2>
          <p className="text-xs text-muted-foreground">Conversations</p>
        </div>
        <div className="p-3">
          <NewConversationDialog
            projectId={projectId}
            open={createOpen}
            onOpenChange={setCreateOpen}
            onCreated={(c) => {
              queryClient.invalidateQueries({
                queryKey: ['conversations', projectId],
              })
              setSelectedId(c.id)
            }}
          />
        </div>
        <div className="flex-1 overflow-y-auto" data-testid="conversation-list">
          {convLoading && (
            <div className="p-4 text-sm text-muted-foreground">Loading…</div>
          )}
          {!convLoading && conversations?.length === 0 && (
            <div className="p-4 text-sm text-muted-foreground">
              No conversations yet. Start one to talk to an agent.
            </div>
          )}
          {conversations?.map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => setSelectedId(c.id)}
              data-testid={`conversation-item-${c.id}`}
              className={`w-full text-left px-4 py-3 border-b hover:bg-accent transition-colors ${
                c.id === selectedId ? 'bg-accent' : ''
              }`}
            >
              <div className="font-medium text-sm truncate">
                {c.title ?? 'Untitled'}
              </div>
              <div className="text-xs text-muted-foreground mt-0.5">
                {new Date(c.updatedAt ?? c.createdAt).toLocaleString()}
              </div>
            </button>
          ))}
        </div>
      </aside>

      {/* Main pane */}
      <main className="flex-1 flex flex-col" data-testid="chat-main">
        {selected ? (
          <ConversationView key={selected.id} conversation={selected} />
        ) : (
          <div className="flex-1 flex items-center justify-center text-muted-foreground">
            <div className="text-center">
              <MessageSquare className="h-12 w-12 mx-auto mb-3 opacity-50" />
              <p>Select or create a conversation to start.</p>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}

function NewConversationDialog({
  projectId,
  open,
  onOpenChange,
  onCreated,
}: {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (c: Conversation) => void
}) {
  const [title, setTitle] = useState('')
  const [agentId, setAgentId] = useState<string>('')

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: agentsApi.list,
  })

  // Agents pinned to this project or unscoped — both are valid chat targets.
  const eligible: Agent[] = useMemo(
    () =>
      (agents ?? []).filter(
        (a) =>
          a.status === 'ACTIVE' &&
          (a.projectId === projectId || a.projectId === null),
      ),
    [agents, projectId],
  )

  const createMutation = useMutation({
    mutationFn: () =>
      conversationsApi.create({
        projectId,
        title: title.trim() || null,
        agentId: agentId || null,
      }),
    onSuccess: (c) => {
      setTitle('')
      setAgentId('')
      onCreated(c)
      onOpenChange(false)
    },
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button
          className="w-full"
          size="sm"
          data-testid="new-conversation-button"
        >
          <Plus className="h-4 w-4 mr-1" />
          New conversation
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Start a new conversation</DialogTitle>
          <DialogDescription>
            Pick the agent that should respond. You can leave the title blank.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="conv-title">Title (optional)</Label>
            <Input
              id="conv-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Debug pipeline X"
              data-testid="new-conversation-title"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="conv-agent">Agent</Label>
            <Select value={agentId} onValueChange={setAgentId}>
              <SelectTrigger id="conv-agent" data-testid="new-conversation-agent">
                <SelectValue placeholder="(no agent — chat will not stream replies)" />
              </SelectTrigger>
              <SelectContent>
                {eligible.map((a) => (
                  <SelectItem key={a.id} value={a.id}>
                    {a.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {eligible.length === 0 && (
              <p className="text-xs text-muted-foreground">
                No active agents are available for this project. Create one
                under Platform → Agents first.
              </p>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => createMutation.mutate()}
            disabled={createMutation.isPending}
            data-testid="new-conversation-submit"
          >
            {createMutation.isPending ? 'Creating…' : 'Create'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ConversationView({ conversation }: { conversation: Conversation }) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)

  const { data: messages } = useQuery({
    queryKey: ['conversation-messages', conversation.id],
    queryFn: () => conversationsApi.messages(conversation.id),
  })

  // In-flight assistant deltas keyed by sequenceNo. Kept separate from the
  // persisted history so we never double-render when the .complete frame
  // and the persisted row both arrive.
  const [streaming, setStreaming] = useState<Map<number, StreamingMessage>>(
    new Map(),
  )

  // ----- WebSocket: live viewer connection -----
  useEffect(() => {
    const token = localStorage.getItem(ACCESS_TOKEN_KEY)
    if (!token) return

    // The dev server proxies HTTP /api but not WS — go straight to the
    // engine origin (configured by Vite). Production deployments either
    // collapse onto a single origin or proxy WS at the edge.
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const url = `${scheme}//${host}/api/v1/conversations/${conversation.id}/stream?token=${encodeURIComponent(token)}`

    let ws: WebSocket
    try {
      ws = new WebSocket(url)
    } catch {
      return
    }

    ws.onmessage = (event) => {
      let frame: unknown
      try {
        frame = JSON.parse(event.data as string)
      } catch {
        return
      }
      if (!frame || typeof frame !== 'object') return
      const f = frame as { type?: string; payload?: Record<string, unknown> }
      const p = f.payload ?? {}

      if (f.type === 'history.message') {
        // Replay from the WS handshake. Push straight into the cached
        // message list — duplicates against the REST fetch are harmless
        // because the IDs match.
        queryClient.setQueryData<ConversationMessage[]>(
          ['conversation-messages', conversation.id],
          (current) => mergeHistory(current ?? [], p),
        )
      } else if (f.type === 'message.delta') {
        const seq = Number(p.sequenceNo ?? 0)
        const chunk = String(p.content ?? '')
        setStreaming((prev) => {
          const next = new Map(prev)
          const existing = next.get(seq) ?? {
            sequenceNo: seq,
            content: '',
            complete: false,
          }
          next.set(seq, { ...existing, content: existing.content + chunk })
          return next
        })
      } else if (f.type === 'message.complete') {
        const seq = Number(p.sequenceNo ?? 0)
        // Mark the in-flight stream complete and refresh the persisted
        // list — the engine will have inserted an ASSISTANT row by now.
        setStreaming((prev) => {
          const next = new Map(prev)
          const existing = next.get(seq)
          if (existing) {
            next.set(seq, { ...existing, complete: true })
          }
          return next
        })
        queryClient.invalidateQueries({
          queryKey: ['conversation-messages', conversation.id],
        })
      } else if (
        f.type === 'approval.request' ||
        f.type === 'approval.decision'
      ) {
        // Phase 7c — either frame mutates the persisted row's approval
        // status; the REST list is the source of truth so just refetch.
        queryClient.invalidateQueries({
          queryKey: ['conversation-messages', conversation.id],
        })
      }
    }

    return () => {
      try {
        ws.close()
      } catch {
        // ignore
      }
    }
  }, [conversation.id, queryClient])

  // Once the persisted list contains the assistant row that corresponds
  // to a buffered stream, drop the streaming entry — REST is now the
  // source of truth.
  useEffect(() => {
    if (!messages || streaming.size === 0) return
    const persistedSeqs = new Set(
      messages.filter((m) => m.role === 'ASSISTANT').map((m) => m.sequenceNo),
    )
    let dirty = false
    const next = new Map(streaming)
    for (const seq of streaming.keys()) {
      if (persistedSeqs.has(seq)) {
        next.delete(seq)
        dirty = true
      }
    }
    if (dirty) setStreaming(next)
  }, [messages, streaming])

  // Auto-scroll on new content.
  useEffect(() => {
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: 'smooth',
    })
  }, [messages, streaming])

  const sendMutation = useMutation({
    mutationFn: (content: string) =>
      conversationsApi.postUserMessage(conversation.id, content),
    onSuccess: () => {
      setDraft('')
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  const handleSend = () => {
    const trimmed = draft.trim()
    if (!trimmed || sendMutation.isPending) return
    sendMutation.mutate(trimmed)
  }

  const sortedStream = useMemo(
    () =>
      Array.from(streaming.values()).sort((a, b) => a.sequenceNo - b.sequenceNo),
    [streaming],
  )

  return (
    <div className="flex flex-col h-full">
      <div className="border-b p-4 flex items-center justify-between bg-card">
        <div>
          <h3 className="font-semibold">{conversation.title ?? 'Untitled'}</h3>
          <p className="text-xs text-muted-foreground">
            {conversation.agentId
              ? `Agent: ${conversation.agentId.substring(0, 8)}…`
              : 'No agent pinned'}
          </p>
        </div>
        <Badge variant="outline">{conversation.status ?? 'ACTIVE'}</Badge>
      </div>

      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto p-6 space-y-4"
        data-testid="message-list"
      >
        {(messages ?? []).map((m) => (
          <MessageBubble key={m.id} message={m} />
        ))}
        {sortedStream.map((s) => (
          <StreamingBubble key={`stream-${s.sequenceNo}`} stream={s} />
        ))}
        {(messages?.length ?? 0) === 0 && sortedStream.length === 0 && (
          <div className="text-center text-muted-foreground py-12">
            No messages yet. Say hello below.
          </div>
        )}
      </div>

      <div className="border-t p-4 bg-card">
        <div className="flex gap-2">
          <Textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                handleSend()
              }
            }}
            placeholder="Type a message — Shift+Enter for newline."
            className="min-h-[60px] resize-none"
            data-testid="message-input"
          />
          <Button
            onClick={handleSend}
            disabled={!draft.trim() || sendMutation.isPending}
            data-testid="send-button"
          >
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

function MessageBubble({ message }: { message: ConversationMessage }) {
  // Phase 7c — approval rows render through a dedicated card that picks
  // a renderer based on payloadJson shape (diff / SQL / shell / generic).
  if (message.role === 'APPROVAL_REQUEST') {
    return <ApprovalCard message={message} />
  }
  if (message.role === 'APPROVAL_RESPONSE') {
    return <ApprovalResponseBubble message={message} />
  }
  const isUser = message.role === 'USER'
  return (
    <div
      className={`flex gap-3 ${isUser ? 'justify-end' : 'justify-start'}`}
      data-testid={`message-${message.role.toLowerCase()}`}
      data-seq={message.sequenceNo}
    >
      {!isUser && (
        <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center shrink-0">
          <Bot className="h-4 w-4 text-primary" />
        </div>
      )}
      <Card
        className={`max-w-[70%] ${isUser ? 'bg-primary text-primary-foreground' : ''}`}
      >
        <CardContent className="p-3 whitespace-pre-wrap text-sm">
          {message.content}
        </CardContent>
      </Card>
      {isUser && (
        <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center shrink-0">
          <User className="h-4 w-4 text-primary" />
        </div>
      )}
    </div>
  )
}

function StreamingBubble({ stream }: { stream: StreamingMessage }) {
  return (
    <div
      className="flex gap-3 justify-start"
      data-testid="message-assistant-streaming"
      data-seq={stream.sequenceNo}
    >
      <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center shrink-0">
        <Bot className="h-4 w-4 text-primary animate-pulse" />
      </div>
      <Card className="max-w-[70%] border-dashed">
        <CardContent className="p-3 whitespace-pre-wrap text-sm">
          {stream.content}
          {!stream.complete && (
            <span className="inline-block w-2 h-4 ml-0.5 bg-current opacity-50 animate-pulse align-middle" />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * Merge a single {@code history.message} frame into the cached list so we
 * never end up with duplicate IDs and so sequence ordering is preserved.
 */
function mergeHistory(
  current: ConversationMessage[],
  payload: Record<string, unknown>,
): ConversationMessage[] {
  const incoming: ConversationMessage = {
    id: String(payload.messageId ?? ''),
    conversationId: String(payload.conversationId ?? ''),
    sequenceNo: Number(payload.sequenceNo ?? 0),
    role: (payload.role as ConversationMessage['role']) ?? 'USER',
    content: String(payload.content ?? ''),
    authorUserId: (payload.authorUserId as string | null) ?? null,
    authorAgentId: (payload.authorAgentId as string | null) ?? null,
    modelCode: (payload.modelCode as string | null) ?? null,
    tokenCount: (payload.tokenCount as number | null) ?? null,
    toolCallId: null,
    parentMessageId: null,
    payloadJson: (payload.payloadJson as string | null) ?? null,
    approvalStatus:
      (payload.approvalStatus as ConversationMessage['approvalStatus']) ?? null,
    approverId: (payload.approverId as string | null) ?? null,
    expiresAt: (payload.expiresAt as string | null) ?? null,
    createdAt: String(payload.createdAt ?? new Date().toISOString()),
  }
  if (current.some((m) => m.id === incoming.id)) return current
  return [...current, incoming].sort((a, b) => a.sequenceNo - b.sequenceNo)
}

// ============================================================================
// Phase 7c — HITL approval card
// ============================================================================

/**
 * Parsed shape of a Phase 7c approval payload. The agent SDK is free to
 * push arbitrary JSON; we render whichever known shape is present, or
 * fall back to a pretty-printed JSON block. {@code clientRequestId} is
 * stripped before rendering because it's plumbing, not user content.
 */
type ApprovalPayloadShape =
  | { kind: 'diff'; oldText: string; newText: string; title?: string }
  | { kind: 'sql'; sql: string }
  | { kind: 'shell'; command: string }
  | { kind: 'json'; data: unknown }
  | { kind: 'empty' }

function parseApprovalPayload(raw: string | null): ApprovalPayloadShape {
  if (!raw || !raw.trim()) return { kind: 'empty' }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { kind: 'json', data: raw }
  }
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    if (typeof obj.oldText === 'string' && typeof obj.newText === 'string') {
      return {
        kind: 'diff',
        oldText: obj.oldText,
        newText: obj.newText,
        title: typeof obj.title === 'string' ? obj.title : undefined,
      }
    }
    if (typeof obj.sql === 'string') return { kind: 'sql', sql: obj.sql }
    if (typeof obj.command === 'string')
      return { kind: 'shell', command: obj.command }
    // Strip plumbing keys before showing as generic JSON.
    const cleaned = { ...obj }
    delete cleaned.clientRequestId
    return { kind: 'json', data: cleaned }
  }
  return { kind: 'json', data: parsed }
}

function ApprovalCard({ message }: { message: ConversationMessage }) {
  const queryClient = useQueryClient()
  const [comment, setComment] = useState('')
  const status = message.approvalStatus ?? 'PENDING'
  const shape = useMemo(
    () => parseApprovalPayload(message.payloadJson),
    [message.payloadJson],
  )

  const decisionMutation = useMutation({
    mutationFn: (decision: 'APPROVED' | 'REJECTED') =>
      conversationsApi.submitApprovalDecision(
        message.conversationId,
        message.id,
        decision,
        comment.trim() || undefined,
      ),
    onSuccess: () => {
      setComment('')
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', message.conversationId],
      })
    },
  })

  const statusBadge = (() => {
    switch (status) {
      case 'APPROVED':
        return <Badge className="bg-emerald-600 text-white">Approved</Badge>
      case 'REJECTED':
        return <Badge variant="destructive">Rejected</Badge>
      case 'EXPIRED':
        return <Badge variant="outline">Expired</Badge>
      default:
        return <Badge className="bg-amber-500 text-white">Pending</Badge>
    }
  })()

  return (
    <div
      className="flex gap-3 justify-center"
      data-testid="message-approval-request"
      data-message-id={message.id}
      data-seq={message.sequenceNo}
      data-status={status}
    >
      <Card className="w-full max-w-[90%] border-amber-300">
        <CardContent className="p-4 space-y-3">
          <div className="flex items-start justify-between gap-3">
            <div>
              <div className="text-sm font-semibold flex items-center gap-2">
                Approval required
                {statusBadge}
              </div>
              {message.content && (
                <p
                  className="text-sm text-muted-foreground mt-1"
                  data-testid="approval-summary"
                >
                  {message.content}
                </p>
              )}
            </div>
            {message.expiresAt && status === 'PENDING' && (
              <span className="text-xs text-muted-foreground whitespace-nowrap">
                expires {new Date(message.expiresAt).toLocaleString()}
              </span>
            )}
          </div>

          <ApprovalPayloadView shape={shape} />

          {status === 'PENDING' && (
            <div className="space-y-2 pt-1">
              <Textarea
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                placeholder="Optional comment for the agent (required for reject in many flows)"
                className="min-h-[44px] resize-none text-sm"
                data-testid="approval-comment"
              />
              <div className="flex gap-2 justify-end">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={decisionMutation.isPending}
                  onClick={() => decisionMutation.mutate('REJECTED')}
                  data-testid="approval-reject"
                >
                  Reject
                </Button>
                <Button
                  size="sm"
                  disabled={decisionMutation.isPending}
                  onClick={() => decisionMutation.mutate('APPROVED')}
                  data-testid="approval-approve"
                >
                  Approve
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function ApprovalPayloadView({ shape }: { shape: ApprovalPayloadShape }) {
  if (shape.kind === 'empty') return null
  if (shape.kind === 'diff') {
    return (
      <div
        className="rounded border bg-muted/40 text-xs font-mono overflow-hidden"
        data-testid="approval-payload-diff"
      >
        {shape.title && (
          <div className="px-3 py-1 border-b bg-muted/60 text-[11px] uppercase tracking-wide">
            {shape.title}
          </div>
        )}
        <div className="grid grid-cols-2 divide-x">
          <pre
            className="p-3 overflow-x-auto bg-red-50 text-red-900"
            data-testid="approval-payload-diff-old"
          >
            {shape.oldText}
          </pre>
          <pre
            className="p-3 overflow-x-auto bg-emerald-50 text-emerald-900"
            data-testid="approval-payload-diff-new"
          >
            {shape.newText}
          </pre>
        </div>
      </div>
    )
  }
  if (shape.kind === 'sql') {
    return (
      <pre
        className="rounded border bg-muted/40 p-3 text-xs font-mono overflow-x-auto"
        data-testid="approval-payload-sql"
      >
        {shape.sql}
      </pre>
    )
  }
  if (shape.kind === 'shell') {
    return (
      <pre
        className="rounded border bg-zinc-900 text-zinc-100 p-3 text-xs font-mono overflow-x-auto"
        data-testid="approval-payload-shell"
      >
        $ {shape.command}
      </pre>
    )
  }
  return (
    <pre
      className="rounded border bg-muted/40 p-3 text-xs font-mono overflow-x-auto"
      data-testid="approval-payload-json"
    >
      {typeof shape.data === 'string'
        ? shape.data
        : JSON.stringify(shape.data, null, 2)}
    </pre>
  )
}

function ApprovalResponseBubble({
  message,
}: {
  message: ConversationMessage
}) {
  return (
    <div
      className="flex gap-3 justify-center"
      data-testid="message-approval-response"
      data-seq={message.sequenceNo}
    >
      <Card className="max-w-[70%] border-dashed">
        <CardContent className="p-3 text-xs text-muted-foreground italic">
          {message.content || 'Decision recorded.'}
        </CardContent>
      </Card>
    </div>
  )
}

