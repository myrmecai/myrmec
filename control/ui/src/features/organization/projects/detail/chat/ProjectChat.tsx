// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  assistantsApi,
  attachmentsApi,
  conversationsApi,
  projectsApi,
  type Assistant,
  type Attachment,
  type Conversation,
  type ConversationEvent,
  type ConversationMessage,
  type ConversationStatus,
  type MessageFeedbackRating,
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
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  ChevronLeft,
  MessageSquare,
  Plus,
  Send,
  Square,
  User,
  Bot,
  Activity,
  Wrench,
  Info,
  Copy,
  Check,
  ChevronDown,
  ChevronRight,
  ArrowDown,
  MoreHorizontal,
  Pencil,
  Archive,
  ArchiveRestore,
  Pin,
  PinOff,
  Code,
  FileText,
  ThumbsUp,
  ThumbsDown,
  Download,
  Link2,
  RefreshCw,
  Paperclip,
  X,
  Loader2,
  Layers,
} from 'lucide-react'
import { MarkdownContent } from '@/components/chat/MarkdownContent'

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

/**
 * Render an ISO timestamp as a short local time (HH:MM). Falls back to the
 * raw string if it cannot be parsed so we never render "Invalid Date".
 */
function formatTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function ProjectChat({ projectId }: { projectId: string }) {
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
              {c.status === 'ARCHIVED' && (
                <Badge variant="outline" className="mt-1 text-[10px]">
                  Archived
                </Badge>
              )}
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
  const [assistantId, setAssistantId] = useState<string>('')

  const { data: assistants } = useQuery({
    queryKey: ['assistants', projectId],
    queryFn: () => assistantsApi.list(projectId),
  })

  // Only published, enabled, non-archived assistants can start a session.
  const startable: Assistant[] = useMemo(
    () =>
      (assistants ?? []).filter(
        (a) => a.currentVersionId != null && !a.disabled && a.archivedAt == null,
      ),
    [assistants],
  )

  const createMutation = useMutation({
    mutationFn: () =>
      conversationsApi.create({
        projectId,
        title: title.trim() || null,
        assistantId: assistantId || null,
      }),
    onSuccess: (c) => {
      setTitle('')
      setAssistantId('')
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
            Pick the assistant you want to talk to. You can leave the title
            blank.
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
            <Label htmlFor="conv-assistant">Assistant</Label>
            <Select value={assistantId} onValueChange={setAssistantId}>
              <SelectTrigger
                id="conv-assistant"
                data-testid="new-conversation-assistant"
              >
                <SelectValue placeholder="Select an assistant" />
              </SelectTrigger>
              <SelectContent>
                {startable.map((a) => (
                  <SelectItem key={a.id} value={a.id}>
                    {a.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {startable.length === 0 && (
              <p className="text-xs text-muted-foreground">
                No published assistants are available for this project. Create
                and publish one under Conversation Management first.
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
            disabled={createMutation.isPending || !assistantId}
            data-testid="new-conversation-submit"
          >
            {createMutation.isPending ? 'Creating…' : 'Create'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// Scrollback pagination: how many of the newest messages to load initially
// and how many more each "Load older" click reveals. The engine clamps the
// per-request page at 200, so the growing window tops out there too.
const MESSAGE_PAGE_SIZE = 50
const MESSAGE_WINDOW_MAX = 200

function ConversationView({ conversation }: { conversation: Conversation }) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState('')
  const [lifecycleOpen, setLifecycleOpen] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  // Live-viewer socket connection state, surfaced as a reconnecting banner.
  const [connState, setConnState] = useState<
    'connecting' | 'open' | 'reconnecting'
  >('connecting')
  // Whether the transcript is scrolled to (near) the bottom. When the user
  // scrolls up to read history we stop auto-scrolling and offer a
  // "jump to latest" affordance instead of yanking them back down.
  const [atBottom, setAtBottom] = useState(true)

  // Owner-only ⋯ menu: rename + archive / unarchive.
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameDraft, setRenameDraft] = useState(conversation.title ?? '')

  // Export / share (#104d): download the transcript as Markdown (engine
  // renders + audits) and copy an ACL-gated permalink to the clipboard.
  const [linkCopied, setLinkCopied] = useState(false)
  const exportMutation = useMutation({
    mutationFn: () => conversationsApi.exportMarkdown(conversation.id),
    onSuccess: (markdown) => {
      const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `conversation-${conversation.id}.md`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    },
  })
  const handleCopyLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setLinkCopied(true)
      setTimeout(() => setLinkCopied(false), 1500)
    } catch {
      // clipboard blocked (insecure context / permissions) — silently ignore
    }
  }

  const updateMutation = useMutation({
    mutationFn: (data: { title?: string; status?: ConversationStatus }) =>
      conversationsApi.update(conversation.id, data),
    onSuccess: () => {
      // Refresh both the open thread and the sidebar list (title / status).
      queryClient.invalidateQueries({
        queryKey: ['conversations', conversation.projectId],
      })
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  // Pin / unpin a single message + "pinned only" transcript filter.
  const [pinnedOnly, setPinnedOnly] = useState(false)
  const pinMutation = useMutation({
    mutationFn: (vars: { messageId: string; pinned: boolean }) =>
      conversationsApi.pinMessage(conversation.id, vars.messageId, vars.pinned),
    onMutate: async (vars) => {
      // Optimistic flip so the pin reacts instantly; the refetch on
      // settle reconciles with the server.
      const key = ['conversation-messages', conversation.id]
      await queryClient.cancelQueries({ queryKey: key })
      const prev = queryClient.getQueryData<ConversationMessage[]>(key)
      queryClient.setQueryData<ConversationMessage[]>(key, (cur) =>
        (cur ?? []).map((m) =>
          m.id === vars.messageId ? { ...m, pinned: vars.pinned } : m,
        ),
      )
      return { prev }
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prev) {
        queryClient.setQueryData(
          ['conversation-messages', conversation.id],
          ctx.prev,
        )
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  // Thumbs-up / thumbs-down feedback on an assistant message (#104a) with
  // an optional free-text reason. Optimistic so the verdict reacts
  // instantly; the refetch on settle reconciles attribution + timestamp.
  const feedbackMutation = useMutation({
    mutationFn: (vars: {
      messageId: string
      rating: MessageFeedbackRating | null
      reason?: string | null
    }) =>
      conversationsApi.rateMessage(
        conversation.id,
        vars.messageId,
        vars.rating,
        vars.reason,
      ),
    onMutate: async (vars) => {
      const key = ['conversation-messages', conversation.id]
      await queryClient.cancelQueries({ queryKey: key })
      const prev = queryClient.getQueryData<ConversationMessage[]>(key)
      queryClient.setQueryData<ConversationMessage[]>(key, (cur) =>
        (cur ?? []).map((m) =>
          m.id === vars.messageId
            ? {
                ...m,
                feedbackRating: vars.rating,
                feedbackReason:
                  vars.rating === null ? null : vars.reason ?? m.feedbackReason,
              }
            : m,
        ),
      )
      return { prev }
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prev) {
        queryClient.setQueryData(
          ['conversation-messages', conversation.id],
          ctx.prev,
        )
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  // Scrollback pagination: a growing window of the newest messages. The
  // query key stays stable (so the WS merge + pin optimistic writes keep
  // working) while the queryFn reads the current window size; "Load older"
  // grows the window and refetches.
  const [windowSize, setWindowSize] = useState(MESSAGE_PAGE_SIZE)
  const { data: messages, refetch: refetchMessages } = useQuery({
    queryKey: ['conversation-messages', conversation.id],
    queryFn: () =>
      conversationsApi.messages(conversation.id, { limit: windowSize }),
  })

  // #88 — poll whether a worker is online to answer, so we can warn before
  // sending that the message will be queued. Cheap, host-scoped count; the
  // engine emits a SYSTEM notice (#86) after the fact, this is the proactive
  // hint. Polled because worker connect/disconnect isn't pushed to this view.
  const { data: agentAvailability } = useQuery({
    queryKey: ['conversation-agent-availability', conversation.id],
    queryFn: () => conversationsApi.agentAvailability(conversation.id),
    refetchInterval: 15000,
    enabled: !!conversation.agentId,
  })

  // There may be older rows to fetch while the last page came back full and
  // we haven't hit the engine's per-request clamp.
  const hasOlderMessages =
    (messages?.length ?? 0) >= windowSize && windowSize < MESSAGE_WINDOW_MAX

  // Preserve the viewport anchor across a "Load older" prepend: remember the
  // distance from the bottom before growing the window, then restore it once
  // the taller transcript has rendered.
  const pendingPrependRef = useRef<number | null>(null)
  const loadOlderMessages = () => {
    const el = scrollRef.current
    pendingPrependRef.current = el ? el.scrollHeight - el.scrollTop : null
    setWindowSize((n) => Math.min(n + MESSAGE_PAGE_SIZE, MESSAGE_WINDOW_MAX))
  }
  // Refetch whenever the window grows.
  useEffect(() => {
    void refetchMessages()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [windowSize])
  // Restore scroll position after older rows are prepended.
  useLayoutEffect(() => {
    if (pendingPrependRef.current == null) return
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight - pendingPrependRef.current
    pendingPrependRef.current = null
  }, [messages])

  // In-flight assistant deltas keyed by sequenceNo. Kept separate from the
  // persisted history so we never double-render when the .complete frame
  // and the persisted row both arrive.
  const [streaming, setStreaming] = useState<Map<number, StreamingMessage>>(
    new Map(),
  )

  // ----- SSE: live viewer connection -----
  useEffect(() => {
    const token = localStorage.getItem(ACCESS_TOKEN_KEY)
    if (!token) return

    // Server-Sent Events over the same-origin HTTP path (the Vite dev
    // server proxies /api). EventSource can't set an Authorization header,
    // so the access token rides along as a ?token= query param — the
    // engine validates it in ConversationStreamController.
    const url = `/api/v1/conversations/${conversation.id}/stream?token=${encodeURIComponent(token)}`

    let es: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let attempt = 0
    let disposed = false

    const handleFrame = (event: MessageEvent) => {
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
        // The engine now broadcasts an enriched history.message-shaped
        // envelope carrying the full persisted row (id, role, createdAt,
        // modelCode, etc.). Insert it directly into the cached list —
        // no REST refetch needed. GET /messages is only used on initial
        // page load and scrollback.
        //
        // To revert to the old behaviour: replace this block with
        //   setStreaming(prev => { ... mark complete ... })
        //   queryClient.invalidateQueries(['conversation-messages', id])
        const seq = Number(p.sequenceNo ?? 0)
        queryClient.setQueryData<ConversationMessage[]>(
          ['conversation-messages', conversation.id],
          (current) => mergeHistory(current ?? [], p),
        )
        // Drop the streaming buffer entry — the REST list now has the row.
        setStreaming((prev) => {
          const next = new Map(prev)
          next.delete(seq)
          return next
        })
      } else if (f.type === 'task.cancelled') {
        // #4 — the worker acknowledged a cancel. Drop the in-flight buffer
        // for this turn; the engine persists any partial text as an
        // ASSISTANT row, so refetch the source-of-truth list.
        const seq = Number(p.sequenceNo ?? 0)
        setStreaming((prev) => {
          const next = new Map(prev)
          next.delete(seq)
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

    const connect = () => {
      if (disposed) return
      setConnState(attempt === 0 ? 'connecting' : 'reconnecting')
      try {
        es = new EventSource(url)
      } catch {
        scheduleReconnect()
        return
      }
      es.onopen = () => {
        attempt = 0
        setConnState('open')
      }
      es.onmessage = handleFrame
      es.onerror = () => {
        // EventSource would auto-reconnect, but we drive it ourselves so the
        // reconnecting banner + capped backoff stay under our control.
        if (disposed) return
        es?.close()
        scheduleReconnect()
      }
    }

    const scheduleReconnect = () => {
      if (disposed) return
      setConnState('reconnecting')
      // Exponential backoff capped at 10s.
      const delay = Math.min(1000 * 2 ** attempt, 10000)
      attempt += 1
      reconnectTimer = setTimeout(connect, delay)
    }

    connect()

    return () => {
      disposed = true
      if (reconnectTimer) clearTimeout(reconnectTimer)
      try {
        es?.close()
      } catch {
        // ignore
      }
    }
  }, [conversation.id, queryClient])

  // Auto-scroll on new content — but only when the user is already at the
  // bottom, so reading scrollback isn't interrupted.
  useEffect(() => {
    if (!atBottom) return
    scrollRef.current?.scrollTo({
      top: scrollRef.current.scrollHeight,
      behavior: 'smooth',
    })
  }, [messages, streaming, atBottom])

  // Track whether the transcript is pinned to the bottom (within a small
  // threshold) so we know when to suppress auto-scroll and show the
  // "jump to latest" control.
  const handleScroll = () => {
    const el = scrollRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    setAtBottom(distance < 80)
  }

  const jumpToLatest = () => {
    const el = scrollRef.current
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    setAtBottom(true)
  }

  const sendMutation = useMutation({
    mutationFn: (content: string) =>
      conversationsApi.postUserMessage(conversation.id, content),
    onSuccess: () => {
      setDraft('')
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
      // Freshly-bound attachments move from the composer to the user bubble.
      queryClient.invalidateQueries({
        queryKey: ['conversation-attachments', conversation.id],
      })
      // The first user turn auto-titles an untitled conversation server-side
      // (#104e); refresh the list so the new title shows in the sidebar/header.
      queryClient.invalidateQueries({
        queryKey: ['conversations', conversation.projectId],
      })
    },
  })

  const handleSend = () => {
    const trimmed = draft.trim()
    if (!trimmed || sendMutation.isPending) return
    sendMutation.mutate(trimmed)
  }

  // #103 — file attachments. Uploads happen immediately (scan-on-upload);
  // clean rows sit unbound until the next user turn binds them server-side.
  // We surface the conversation's attachments so we can render unbound
  // "staged" chips in the composer and bound chips on user bubbles.
  const fileInputRef = useRef<HTMLInputElement>(null)
  const { data: attachments } = useQuery({
    queryKey: ['conversation-attachments', conversation.id],
    queryFn: () => attachmentsApi.list(conversation.id),
  })

  const uploadMutation = useMutation({
    mutationFn: (file: File) => attachmentsApi.upload(conversation.id, file),
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-attachments', conversation.id],
      })
    },
  })

  const removeAttachmentMutation = useMutation({
    mutationFn: (attachmentId: string) =>
      attachmentsApi.delete(conversation.id, attachmentId),
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-attachments', conversation.id],
      })
    },
  })

  const handlePickFiles = (files: FileList | null) => {
    if (!files) return
    for (const file of Array.from(files)) {
      uploadMutation.mutate(file)
    }
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  // Attachments not yet tied to a turn — shown as staged chips in the
  // composer. Includes quarantined uploads (rendered as blocked) so the
  // user sees why a file won't be sent.
  const stagedAttachments = useMemo(
    () => (attachments ?? []).filter((a) => a.messageId == null),
    [attachments],
  )

  // Bound attachments grouped by their message id, for per-bubble chips.
  const attachmentsByMessage = useMemo(() => {
    const map = new Map<string, Attachment[]>()
    for (const a of attachments ?? []) {
      if (a.messageId == null) continue
      const list = map.get(a.messageId) ?? []
      list.push(a)
      map.set(a.messageId, list)
    }
    return map
  }, [attachments])

  const cancelMutation = useMutation({
    mutationFn: () => conversationsApi.cancelTurn(conversation.id),
  })

  // Edit a prior USER turn and resend it (#104b). Forks the active branch:
  // the replaced rows are soft-superseded server-side and a fresh turn is
  // dispatched. Refetch to pull the new branch + drop the superseded rows.
  const editMutation = useMutation({
    mutationFn: (vars: { messageId: string; content: string }) =>
      conversationsApi.editAndResend(
        conversation.id,
        vars.messageId,
        vars.content,
      ),
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  // Regenerate an ASSISTANT answer (#104b). Soft-supersedes the answer and
  // re-dispatches the same preceding USER turn.
  const regenerateMutation = useMutation({
    mutationFn: (messageId: string) =>
      conversationsApi.regenerate(conversation.id, messageId),
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: ['conversation-messages', conversation.id],
      })
    },
  })

  const sortedStream = useMemo(
    () =>
      Array.from(streaming.values()).sort((a, b) => a.sequenceNo - b.sequenceNo),
    [streaming],
  )

  // A turn is in flight while any buffered stream is not yet marked complete.
  const isStreaming = useMemo(
    () => sortedStream.some((s) => !s.complete),
    [sortedStream],
  )

  const pinnedCount = useMemo(
    () => (messages ?? []).filter((m) => m.pinned).length,
    [messages],
  )
  const visibleMessages = useMemo(
    () => {
      // #104b — superseded (edited / regenerated) rows are retained server-side
      // for transparency but hidden from the active transcript.
      const active = (messages ?? []).filter((m) => !m.superseded)
      return pinnedOnly ? active.filter((m) => m.pinned) : active
    },
    [messages, pinnedOnly],
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
        <div className="flex items-center gap-2">
          {agentAvailability && (
            <Badge
              variant="outline"
              className={
                agentAvailability.online
                  ? 'border-emerald-300 text-emerald-700 dark:border-emerald-800 dark:text-emerald-300'
                  : 'border-amber-300 text-amber-700 dark:border-amber-800 dark:text-amber-300'
              }
              title={
                agentAvailability.online
                  ? `${agentAvailability.connectedCount} agent(s) online · ${agentAvailability.idleCount} idle`
                  : 'No agent online — your message will be queued until one connects'
              }
              data-testid="agent-availability"
            >
              <span
                className={`mr-1 h-1.5 w-1.5 rounded-full ${
                  agentAvailability.online ? 'bg-emerald-500' : 'bg-amber-500'
                }`}
              />
              {agentAvailability.online ? 'Agents online' : 'No agents online'}
            </Badge>
          )}
          <Button
            variant={pinnedOnly ? 'default' : 'outline'}
            size="sm"
            onClick={() => setPinnedOnly((v) => !v)}
            disabled={!pinnedOnly && pinnedCount === 0}
            title={
              pinnedCount === 0
                ? 'No pinned messages yet'
                : pinnedOnly
                  ? 'Show all messages'
                  : 'Show pinned messages only'
            }
            data-testid="pinned-filter"
            aria-pressed={pinnedOnly}
          >
            <Pin className="h-4 w-4 mr-1" />
            Pinned{pinnedCount > 0 ? ` (${pinnedCount})` : ''}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setLifecycleOpen(true)}
            title="View the worker-bind lifecycle log"
          >
            <Activity className="h-4 w-4 mr-1" />
            Lifecycle
          </Button>
          <Badge variant="outline">{conversation.status ?? 'ACTIVE'}</Badge>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                title="Conversation actions"
                data-testid="conversation-menu"
              >
                <MoreHorizontal className="h-4 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                onSelect={() => {
                  setRenameDraft(conversation.title ?? '')
                  setRenameOpen(true)
                }}
                data-testid="conversation-rename"
              >
                <Pencil className="h-4 w-4 mr-2" />
                Rename
              </DropdownMenuItem>
              <DropdownMenuItem
                onSelect={() => exportMutation.mutate()}
                data-testid="conversation-export"
              >
                <Download className="h-4 w-4 mr-2" />
                Export as Markdown
              </DropdownMenuItem>
              <DropdownMenuItem
                onSelect={(e) => {
                  e.preventDefault()
                  handleCopyLink()
                }}
                data-testid="conversation-copy-link"
              >
                <Link2 className="h-4 w-4 mr-2" />
                {linkCopied ? 'Link copied' : 'Copy link'}
              </DropdownMenuItem>
              {conversation.status === 'ARCHIVED' ? (
                <DropdownMenuItem
                  onSelect={() => updateMutation.mutate({ status: 'ACTIVE' })}
                  data-testid="conversation-unarchive"
                >
                  <ArchiveRestore className="h-4 w-4 mr-2" />
                  Unarchive
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem
                  onSelect={() => updateMutation.mutate({ status: 'ARCHIVED' })}
                  data-testid="conversation-archive"
                >
                  <Archive className="h-4 w-4 mr-2" />
                  Archive
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      <Dialog open={renameOpen} onOpenChange={setRenameOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Rename conversation</DialogTitle>
            <DialogDescription>
              Give this conversation a clearer title.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="rename-title">Title</Label>
            <Input
              id="rename-title"
              value={renameDraft}
              onChange={(e) => setRenameDraft(e.target.value)}
              placeholder="Untitled"
              maxLength={255}
              data-testid="conversation-rename-input"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  const next = renameDraft.trim()
                  if (next) {
                    updateMutation.mutate({ title: next })
                    setRenameOpen(false)
                  }
                }
              }}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenameOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                const next = renameDraft.trim()
                if (next) {
                  updateMutation.mutate({ title: next })
                  setRenameOpen(false)
                }
              }}
              disabled={!renameDraft.trim() || updateMutation.isPending}
              data-testid="conversation-rename-save"
            >
              Save
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {connState !== 'open' && (
        <div
          className="flex items-center justify-center gap-2 border-b bg-amber-50 px-4 py-1.5 text-xs text-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
          role="status"
          data-testid="connection-banner"
        >
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-500" />
          {connState === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
        </div>
      )}

      <div className="flex flex-1 overflow-hidden">
        <div className="relative flex-1 overflow-hidden">
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="h-full overflow-y-auto p-6 space-y-4"
          data-testid="message-list"
          role="log"
          aria-live="polite"
          aria-label="Conversation transcript"
        >
          {!pinnedOnly && hasOlderMessages && (
            <div className="flex justify-center pb-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={loadOlderMessages}
                data-testid="load-older-messages"
              >
                Load older messages
              </Button>
            </div>
          )}
          {visibleMessages.map((m) => (
            <MessageBubble
              key={m.id}
              message={m}
              attachments={attachmentsByMessage.get(m.id)}
              conversationId={conversation.id}
              onTogglePin={(msg) =>
                pinMutation.mutate({ messageId: msg.id, pinned: !msg.pinned })
              }
              onFeedback={(msg, rating, reason) =>
                feedbackMutation.mutate({
                  messageId: msg.id,
                  rating,
                  reason,
                })
              }
              onEdit={(msg, content) =>
                editMutation.mutate({ messageId: msg.id, content })
              }
              onRegenerate={(msg) => regenerateMutation.mutate(msg.id)}
            />
          ))}
          {!pinnedOnly &&
            sortedStream.map((s) => (
              <StreamingBubble key={`stream-${s.sequenceNo}`} stream={s} />
            ))}
          {pinnedOnly && visibleMessages.length === 0 && (
            <div className="text-center text-muted-foreground py-12">
              No pinned messages. Pin a message to keep it here.
            </div>
          )}
          {!pinnedOnly &&
            (messages?.length ?? 0) === 0 &&
            sortedStream.length === 0 && (
              <div className="text-center text-muted-foreground py-12">
                No messages yet. Say hello below.
              </div>
            )}
        </div>

        {!atBottom && (
          <Button
            type="button"
            size="sm"
            variant="secondary"
            onClick={jumpToLatest}
            className="absolute bottom-4 left-1/2 -translate-x-1/2 shadow-md"
            data-testid="jump-to-latest"
          >
            <ArrowDown className="h-4 w-4 mr-1" />
            Jump to latest
          </Button>
        )}
        </div>
      </div>

      <div className="border-t p-4 bg-card">
        {(stagedAttachments.length > 0 || uploadMutation.isPending) && (
          <div
            className="mb-2 flex flex-wrap gap-2"
            data-testid="composer-attachments"
          >
            {stagedAttachments.map((a) => (
              <AttachmentChip
                key={a.id}
                attachment={a}
                conversationId={conversation.id}
                onRemove={() => removeAttachmentMutation.mutate(a.id)}
              />
            ))}
            {uploadMutation.isPending && (
              <span className="inline-flex items-center gap-1.5 rounded-full border bg-muted/50 px-2.5 py-1 text-xs text-muted-foreground">
                <Loader2 className="h-3 w-3 animate-spin" />
                Uploading…
              </span>
            )}
          </div>
        )}
        {uploadMutation.isError && (
          <p
            className="mb-2 text-xs text-destructive"
            data-testid="composer-attachment-error"
          >
            {(uploadMutation.error as Error)?.message ?? 'Upload failed.'}
          </p>
        )}
        <div className="flex gap-2">
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => handlePickFiles(e.target.files)}
            data-testid="composer-file-input"
          />
          <Button
            variant="outline"
            size="icon"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploadMutation.isPending}
            title="Attach files"
            data-testid="composer-attach"
          >
            <Paperclip className="h-4 w-4" />
          </Button>
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
          {isStreaming && (
            <Button
              variant="outline"
              onClick={() => cancelMutation.mutate()}
              disabled={cancelMutation.isPending}
              title="Stop generating"
              data-testid="composer-stop"
            >
              <Square className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>

      <ConversationEventsDialog
        conversation={conversation}
        open={lifecycleOpen}
        onOpenChange={setLifecycleOpen}
      />
    </div>
  )
}

function ConversationEventsDialog({
  conversation,
  open,
  onOpenChange,
}: {
  conversation: Conversation
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { data: events, isLoading, error } = useQuery({
    queryKey: ['conversation-events', conversation.id],
    queryFn: () => conversationsApi.events(conversation.id),
    enabled: open,
    refetchInterval: open ? 5000 : false,
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Lifecycle log</DialogTitle>
          <DialogDescription>
            Worker reservation and bind transitions for this conversation.
            {conversation.agentHostId
              ? ` Host ${conversation.agentHostId.slice(0, 8)}…`
              : ' No host bound yet.'}
          </DialogDescription>
        </DialogHeader>

        {isLoading && (
          <div className="text-muted-foreground py-6">Loading lifecycle…</div>
        )}
        {error && (
          <div className="text-destructive py-6">Failed to load lifecycle log</div>
        )}
        {events && events.length === 0 && (
          <div className="text-center text-muted-foreground py-8">
            No lifecycle events recorded yet.
          </div>
        )}
        {events && events.length > 0 && (
          <ol className="relative border-l ml-3 space-y-4 py-2">
            {events.map((e: ConversationEvent) => (
              <li key={e.id} className="ml-4">
                <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full bg-primary" />
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge variant="secondary" className="font-mono">#{e.seq}</Badge>
                  <Badge variant="outline">{e.reasonCode}</Badge>
                  <span className="text-sm font-medium">
                    {e.fromState ?? '∅'} → {e.toState}
                  </span>
                  {e.bindAttemptNo > 0 && (
                    <span className="text-xs text-muted-foreground">
                      attempt {e.bindAttemptNo}
                    </span>
                  )}
                </div>
                <div className="text-xs text-muted-foreground mt-1">
                  {new Date(e.occurredAt).toLocaleString()}
                  {e.agentId ? ` · worker ${e.agentId.slice(0, 8)}…` : ''}
                </div>
              </li>
            ))}
          </ol>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** Human-readable byte size for attachment chips. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * A single attachment chip (#103). Clean rows are a download link; an
 * optional remove button (composer-staged rows) deletes the upload.
 * Quarantined rows render blocked with the detected threat — never
 * downloadable.
 */
function AttachmentChip({
  attachment,
  conversationId,
  onRemove,
}: {
  attachment: Attachment
  conversationId: string
  onRemove?: () => void
}) {
  const blocked = attachment.scanStatus !== 'CLEAN'
  const label = `${attachment.filename} · ${formatBytes(attachment.sizeBytes)}`

  return (
    <span
      className={`inline-flex max-w-[240px] items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs ${
        blocked
          ? 'border-destructive/50 bg-destructive/10 text-destructive'
          : 'bg-muted/50'
      }`}
      data-testid="attachment-chip"
      data-status={attachment.scanStatus}
      title={
        blocked
          ? `Blocked: ${attachment.scanThreat ?? 'failed scan'}`
          : attachment.filename
      }
    >
      <Paperclip className="h-3 w-3 shrink-0" />
      {blocked ? (
        <span className="truncate">{label} — blocked</span>
      ) : (
        <a
          href={attachmentsApi.contentPath(conversationId, attachment.id)}
          target="_blank"
          rel="noreferrer"
          className="truncate hover:underline"
          data-testid="attachment-download"
        >
          {label}
        </a>
      )}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          className="shrink-0 rounded-full p-0.5 hover:bg-muted"
          title="Remove"
          data-testid="attachment-remove"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </span>
  )
}

function MessageBubble({
  message,
  attachments,
  conversationId,
  onTogglePin,
  onFeedback,
  onEdit,
  onRegenerate,
}: {
  message: ConversationMessage
  attachments?: Attachment[]
  conversationId: string
  onTogglePin?: (message: ConversationMessage) => void
  onFeedback?: (
    message: ConversationMessage,
    rating: MessageFeedbackRating | null,
    reason?: string | null,
  ) => void
  onEdit?: (message: ConversationMessage, content: string) => void
  onRegenerate?: (message: ConversationMessage) => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(message.content)
  // Phase 7c — approval rows render through a dedicated card that picks
  // a renderer based on payloadJson shape (diff / SQL / shell / generic).
  if (message.role === 'APPROVAL_REQUEST') {
    return <ApprovalCard message={message} />
  }
  if (message.role === 'APPROVAL_RESPONSE') {
    return <ApprovalResponseBubble message={message} />
  }
  if (message.role === 'TOOL') {
    return <ToolMessageCard message={message} />
  }
  if (message.role === 'SYSTEM') {
    return <SystemNotice message={message} />
  }
  if (message.role === 'CONTEXT_SUMMARY') {
    return <ContextSummaryNotice message={message} />
  }
  const isUser = message.role === 'USER'

  function submitEdit() {
    const trimmed = draft.trim()
    if (!trimmed || trimmed === message.content) {
      setEditing(false)
      setDraft(message.content)
      return
    }
    onEdit?.(message, trimmed)
    setEditing(false)
  }

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
      <div className={`flex flex-col gap-1 max-w-[70%] ${isUser ? 'items-end' : 'items-start'}`}>
        <Card
          className={`${isUser ? 'bg-primary text-primary-foreground' : ''} ${
            message.pinned ? 'ring-1 ring-amber-400' : ''
          }`}
        >
          <CardContent className="p-3 text-sm">
            {editing ? (
              <div className="flex flex-col gap-2">
                <textarea
                  className="min-w-[260px] resize-y rounded border bg-background p-2 text-sm text-foreground"
                  rows={3}
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  data-testid="message-edit-input"
                  autoFocus
                />
                <div className="flex justify-end gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setEditing(false)
                      setDraft(message.content)
                    }}
                    data-testid="message-edit-cancel"
                  >
                    Cancel
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    onClick={submitEdit}
                    data-testid="message-edit-save"
                  >
                    Save &amp; resend
                  </Button>
                </div>
              </div>
            ) : (
              <MessageBody message={message} />
            )}
          </CardContent>
        </Card>
        {!editing && attachments && attachments.length > 0 && (
          <div
            className={`flex flex-wrap gap-2 ${isUser ? 'justify-end' : 'justify-start'}`}
            data-testid="message-attachments"
          >
            {attachments.map((a) => (
              <AttachmentChip
                key={a.id}
                attachment={a}
                conversationId={conversationId}
              />
            ))}
          </div>
        )}
        {!editing && (
          <MessageMeta
            message={message}
            onTogglePin={onTogglePin}
            onFeedback={onFeedback}
            onEdit={onEdit ? () => setEditing(true) : undefined}
            onRegenerate={onRegenerate}
          />
        )}
      </div>
      {isUser && (
        <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center shrink-0">
          <User className="h-4 w-4 text-primary" />
        </div>
      )}
    </div>
  )
}

/**
 * Message body renderer. ASSISTANT content is rendered as sanitised
 * markdown (#104c) with a hover "view raw" toggle; USER content stays
 * verbatim plain text (we never reinterpret a user's literal input as
 * markup).
 */
function MessageBody({ message }: { message: ConversationMessage }) {
  const [raw, setRaw] = useState(false)
  if (message.role !== 'ASSISTANT') {
    return <div className="whitespace-pre-wrap">{message.content}</div>
  }
  return (
    <div className="group/body relative">
      <button
        type="button"
        onClick={() => setRaw((v) => !v)}
        className="absolute -top-1 right-0 z-10 hidden items-center gap-1 rounded px-1 py-0.5 text-[10px] text-muted-foreground hover:text-foreground group-hover/body:inline-flex"
        title={raw ? 'View formatted' : 'View raw'}
        data-testid="message-raw-toggle"
        aria-pressed={raw}
      >
        {raw ? <FileText className="h-3 w-3" /> : <Code className="h-3 w-3" />}
        {raw ? 'Formatted' : 'Raw'}
      </button>
      {raw ? (
        <pre className="whitespace-pre-wrap text-xs" data-testid="message-raw">
          {message.content}
        </pre>
      ) : (
        <MarkdownContent content={message.content} />
      )}
    </div>
  )
}

/**
 * Per-message footer chrome (UC-014 ③): relative timestamp, model badge
 * (when the engine attributed one), a copy-to-clipboard affordance, and a
 * pin toggle. Rendered for USER/ASSISTANT bubbles only — approval/tool/system
 * rows carry their own chrome.
 */
function MessageMeta({
  message,
  onTogglePin,
  onFeedback,
  onEdit,
  onRegenerate,
}: {
  message: ConversationMessage
  onTogglePin?: (message: ConversationMessage) => void
  onFeedback?: (
    message: ConversationMessage,
    rating: MessageFeedbackRating | null,
    reason?: string | null,
  ) => void
  onEdit?: () => void
  onRegenerate?: (message: ConversationMessage) => void
}) {
  return (
    <div className="flex items-center gap-2 px-1 text-[11px] text-muted-foreground">
      <span data-testid="message-timestamp">{formatTime(message.createdAt)}</span>
      {message.modelCode && (
        <Badge variant="outline" className="h-4 px-1 text-[10px] font-normal">
          {message.modelCode}
        </Badge>
      )}
      <CopyButton text={message.content} />
      {onFeedback && message.role === 'ASSISTANT' && (
        <FeedbackControl message={message} onFeedback={onFeedback} />
      )}
      {onEdit && message.role === 'USER' && (
        <button
          type="button"
          onClick={onEdit}
          className="inline-flex items-center gap-1 transition-colors hover:text-foreground"
          title="Edit & resend"
          data-testid="message-edit"
        >
          <Pencil className="h-3 w-3" />
        </button>
      )}
      {onRegenerate && message.role === 'ASSISTANT' && (
        <button
          type="button"
          onClick={() => onRegenerate(message)}
          className="inline-flex items-center gap-1 transition-colors hover:text-foreground"
          title="Regenerate response"
          data-testid="message-regenerate"
        >
          <RefreshCw className="h-3 w-3" />
        </button>
      )}
      {onTogglePin && (
        <button
          type="button"
          onClick={() => onTogglePin(message)}
          className={`inline-flex items-center gap-1 transition-colors hover:text-foreground ${
            message.pinned ? 'text-amber-500' : ''
          }`}
          title={message.pinned ? 'Unpin message' : 'Pin message'}
          data-testid="message-pin"
          aria-pressed={message.pinned}
        >
          {message.pinned ? (
            <PinOff className="h-3 w-3" />
          ) : (
            <Pin className="h-3 w-3" />
          )}
        </button>
      )}
    </div>
  )
}

function FeedbackControl({
  message,
  onFeedback,
}: {
  message: ConversationMessage
  onFeedback: (
    message: ConversationMessage,
    rating: MessageFeedbackRating | null,
    reason?: string | null,
  ) => void
}) {
  const [noteOpen, setNoteOpen] = useState(false)
  const [reason, setReason] = useState(message.feedbackReason ?? '')
  const rating = message.feedbackRating

  const rate = (next: MessageFeedbackRating) => {
    if (rating === next) {
      onFeedback(message, null)
    } else {
      onFeedback(message, next, message.feedbackReason)
    }
  }

  const saveNote = () => {
    onFeedback(message, rating ?? 'DOWN', reason.trim() ? reason.trim() : null)
    setNoteOpen(false)
  }

  return (
    <span
      className="inline-flex items-center gap-1"
      data-testid="message-feedback"
    >
      <button
        type="button"
        onClick={() => rate('UP')}
        className={`inline-flex items-center transition-colors hover:text-foreground ${
          rating === 'UP' ? 'text-emerald-500' : ''
        }`}
        title={rating === 'UP' ? 'Remove thumbs up' : 'Good response'}
        data-testid="message-feedback-up"
        aria-pressed={rating === 'UP'}
      >
        <ThumbsUp className="h-3 w-3" />
      </button>
      <button
        type="button"
        onClick={() => rate('DOWN')}
        className={`inline-flex items-center transition-colors hover:text-foreground ${
          rating === 'DOWN' ? 'text-red-500' : ''
        }`}
        title={rating === 'DOWN' ? 'Remove thumbs down' : 'Bad response'}
        data-testid="message-feedback-down"
        aria-pressed={rating === 'DOWN'}
      >
        <ThumbsDown className="h-3 w-3" />
      </button>
      {rating && (
        <Dialog
          open={noteOpen}
          onOpenChange={(o) => {
            setNoteOpen(o)
            if (o) setReason(message.feedbackReason ?? '')
          }}
        >
          <DialogTrigger asChild>
            <button
              type="button"
              className={`inline-flex items-center transition-colors hover:text-foreground ${
                message.feedbackReason ? 'text-foreground' : ''
              }`}
              title={message.feedbackReason ? 'Edit note' : 'Add a note'}
              data-testid="message-feedback-note"
            >
              <MessageSquare className="h-3 w-3" />
            </button>
          </DialogTrigger>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>Feedback note</DialogTitle>
              <DialogDescription>
                Optional — tell us why this response was{' '}
                {rating === 'UP' ? 'helpful' : 'not helpful'}.
              </DialogDescription>
            </DialogHeader>
            <Textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="What worked or what went wrong?"
              rows={4}
              maxLength={2000}
              data-testid="message-feedback-reason"
            />
            <DialogFooter>
              <Button variant="ghost" onClick={() => setNoteOpen(false)}>
                Cancel
              </Button>
              <Button onClick={saveNote} data-testid="message-feedback-save">
                Save
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </span>
  )
}

/**
 * Copy-to-clipboard button with a transient checkmark confirmation.
 */
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // clipboard blocked (insecure context / permissions) — silently ignore
    }
  }
  return (
    <button
      type="button"
      onClick={handleCopy}
      className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
      title="Copy message"
      data-testid="message-copy"
    >
      {copied ? (
        <Check className="h-3 w-3" />
      ) : (
        <Copy className="h-3 w-3" />
      )}
    </button>
  )
}

/**
 * Tool-status card (UC-014 ③). A {@code TOOL} role row carries the result
 * of an agent tool invocation; render it as a distinct, collapsible inline
 * card rather than a chat bubble so the transcript stays readable.
 */
function ToolMessageCard({ message }: { message: ConversationMessage }) {
  const [open, setOpen] = useState(false)
  const label = message.toolCallId ? `Tool · ${message.toolCallId}` : 'Tool'
  return (
    <div
      className="flex justify-start"
      data-testid="message-tool"
      data-seq={message.sequenceNo}
    >
      <Card className="w-full max-w-[80%] border-dashed bg-muted/40">
        <CardContent className="p-0">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="flex w-full items-center gap-2 p-3 text-left text-xs font-medium text-muted-foreground hover:text-foreground"
          >
            {open ? (
              <ChevronDown className="h-3.5 w-3.5 shrink-0" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 shrink-0" />
            )}
            <Wrench className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">{label}</span>
          </button>
          {open && (
            <pre className="overflow-x-auto border-t px-3 py-2 text-xs whitespace-pre-wrap">
              {message.content || '(no output)'}
            </pre>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * System notice (UC-014 ③). A {@code SYSTEM} role row is a centered, muted
 * status line (greeting, "resumed", context-summary marker) — not a
 * conversational bubble.
 */
function SystemNotice({ message }: { message: ConversationMessage }) {
  return (
    <div
      className="flex justify-center"
      data-testid="message-system"
      data-seq={message.sequenceNo}
    >
      <div className="flex items-center gap-2 rounded-full bg-muted px-3 py-1 text-xs text-muted-foreground max-w-[80%]">
        <Info className="h-3.5 w-3.5 shrink-0" />
        <span className="whitespace-pre-wrap">{message.content}</span>
      </div>
    </div>
  )
}

/**
 * #8a — context-summary transparency marker. A {@code CONTEXT_SUMMARY} row is
 * an engine-generated running summary that folds earlier turns out of the
 * model's window; the originals stay in the transcript above. Render it as a
 * centered, collapsible "Earlier conversation summarised" marker so the reader
 * can see what the assistant now treats as its memory of the earlier thread.
 */
function ContextSummaryNotice({ message }: { message: ConversationMessage }) {
  const [open, setOpen] = useState(false)
  let foldedCount: number | null = null
  if (message.payloadJson) {
    try {
      const marker = JSON.parse(message.payloadJson) as {
        summarizedMessageCount?: number
      }
      if (typeof marker.summarizedMessageCount === 'number') {
        foldedCount = marker.summarizedMessageCount
      }
    } catch {
      foldedCount = null
    }
  }
  const label =
    foldedCount != null
      ? `Earlier conversation summarised (${foldedCount} message${foldedCount === 1 ? '' : 's'})`
      : 'Earlier conversation summarised'
  return (
    <div
      className="flex justify-center"
      data-testid="message-context-summary"
      data-seq={message.sequenceNo}
    >
      <div className="w-full max-w-[80%] rounded-lg border border-dashed bg-muted/40">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs font-medium text-muted-foreground hover:text-foreground"
          data-testid="context-summary-toggle"
        >
          {open ? (
            <ChevronDown className="h-3.5 w-3.5 shrink-0" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 shrink-0" />
          )}
          <Layers className="h-3.5 w-3.5 shrink-0" />
          <span className="truncate">{label}</span>
        </button>
        {open && (
          <div className="border-t px-3 py-2 text-xs whitespace-pre-wrap text-muted-foreground">
            {message.content || '(empty summary)'}
          </div>
        )}
      </div>
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
    pinned: Boolean(payload.pinned ?? false),
    feedbackRating:
      (payload.feedbackRating as MessageFeedbackRating | null) ?? null,
    feedbackReason: (payload.feedbackReason as string | null) ?? null,
    feedbackBy: (payload.feedbackBy as string | null) ?? null,
    feedbackAt: (payload.feedbackAt as string | null) ?? null,
    superseded: Boolean(payload.superseded ?? false),
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