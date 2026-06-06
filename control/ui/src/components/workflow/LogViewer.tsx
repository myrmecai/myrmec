import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { workflowsApi, type ExecutionEvent, type LogSource } from '@/lib/api'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  AlertCircle,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Code,
  Loader2,
  Pause,
  Play,
  Terminal,
  Wrench,
} from 'lucide-react'

interface LogViewerProps {
  projectId: string
  workflowId: string
  requestId: string
  taskId?: string
  isLive?: boolean
  className?: string
}

const logLevelColors: Record<string, string> = {
  DEBUG: 'text-gray-400',
  INFO: 'text-blue-400',
  WARN: 'text-yellow-400',
  ERROR: 'text-red-400',
}

const logLevelBadgeColors: Record<string, string> = {
  DEBUG: 'bg-gray-600',
  INFO: 'bg-blue-600',
  WARN: 'bg-yellow-600',
  ERROR: 'bg-red-600',
}

// Source badge styles
const sourceBadgeConfig: Record<LogSource, { color: string; icon: typeof Code; label: string }> = {
  TASK: { color: 'bg-teal-600/30 text-teal-400 border-teal-600/50', icon: Code, label: 'Task' },
  AGENT: { color: 'bg-purple-600/30 text-purple-400 border-purple-600/50', icon: Bot, label: 'Agent' },
  SYSTEM: { color: 'bg-orange-600/30 text-orange-400 border-orange-600/50', icon: Terminal, label: 'System' },
}

export function LogViewer({
  projectId,
  workflowId,
  requestId,
  taskId,
  isLive = true,
  className,
}: LogViewerProps) {
  const [events, setEvents] = useState<ExecutionEvent[]>([])
  const [isPaused, setIsPaused] = useState(false)
  const [expandedEvents, setExpandedEvents] = useState<Set<string>>(new Set())
  const [connected, setConnected] = useState(false)
  const [reconnecting, setReconnecting] = useState(false)
  const [activeSources, setActiveSources] = useState<Set<LogSource>>(
    new Set(['TASK', 'AGENT', 'SYSTEM'])
  )
  const scrollRef = useRef<HTMLDivElement>(null)
  const eventSourceRef = useRef<EventSource | null>(null)
  const lastEventTimeRef = useRef<string | null>(null)

  // Load initial events
  const { data: initialEvents, isLoading } = useQuery({
    queryKey: ['execution-events', projectId, workflowId, requestId, taskId],
    queryFn: () =>
      taskId
        ? workflowsApi.listTaskEvents(projectId, workflowId, requestId, taskId)
        : workflowsApi.listEvents(projectId, workflowId, requestId),
  })

  // Set initial events
  useEffect(() => {
    if (initialEvents) {
      setEvents(initialEvents)
      if (initialEvents.length > 0) {
        lastEventTimeRef.current = initialEvents[initialEvents.length - 1].createdAt
      }
    }
  }, [initialEvents])

  // SSE connection for live events
  useEffect(() => {
    if (!isLive || isPaused) {
      return
    }

    const sseUrl = `/api/v1/projects/${projectId}/workflows/${workflowId}/requests/${requestId}/events/stream`
    
    const connect = () => {
      const eventSource = new EventSource(sseUrl)
      eventSourceRef.current = eventSource

      eventSource.addEventListener('connected', () => {
        setConnected(true)
        setReconnecting(false)
      })

      eventSource.addEventListener('log', (e) => {
        const event: ExecutionEvent = JSON.parse(e.data)
        if (!taskId || event.taskId === taskId) {
          addEvent(event)
        }
      })

      eventSource.addEventListener('progress', (e) => {
        const event: ExecutionEvent = JSON.parse(e.data)
        if (!taskId || event.taskId === taskId) {
          addEvent(event)
        }
      })

      eventSource.addEventListener('tool_call', (e) => {
        const event: ExecutionEvent = JSON.parse(e.data)
        if (!taskId || event.taskId === taskId) {
          addEvent(event)
        }
      })

      eventSource.addEventListener('tool_result', (e) => {
        const event: ExecutionEvent = JSON.parse(e.data)
        if (!taskId || event.taskId === taskId) {
          addEvent(event)
        }
      })

      eventSource.addEventListener('status_change', (e) => {
        const event: ExecutionEvent = JSON.parse(e.data)
        if (!taskId || event.taskId === taskId) {
          addEvent(event)
        }
      })

      eventSource.addEventListener('heartbeat', () => {
        // Heartbeat received, connection is alive
      })

      eventSource.onerror = () => {
        setConnected(false)
        eventSource.close()
        
        // Reconnect after 3 seconds
        setReconnecting(true)
        setTimeout(() => {
          if (eventSourceRef.current === eventSource) {
            connect()
          }
        }, 3000)
      }
    }

    connect()

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
        eventSourceRef.current = null
      }
      setConnected(false)
    }
  }, [isLive, isPaused, projectId, workflowId, requestId, taskId])

  const addEvent = useCallback((event: ExecutionEvent) => {
    setEvents((prev) => {
      // Avoid duplicates
      if (prev.some((e) => e.id === event.id)) {
        return prev
      }
      return [...prev, event]
    })
  }, [])

  // Auto-scroll to bottom when new events arrive
  useEffect(() => {
    if (scrollRef.current && !isPaused) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [events, isPaused])

  const toggleExpanded = (eventId: string) => {
    setExpandedEvents((prev) => {
      const next = new Set(prev)
      if (next.has(eventId)) {
        next.delete(eventId)
      } else {
        next.add(eventId)
      }
      return next
    })
  }

  const formatTime = (timestamp: string) => {
    const date = new Date(timestamp)
    return date.toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  }

  const toggleSource = (source: LogSource) => {
    setActiveSources((prev) => {
      const next = new Set(prev)
      if (next.has(source)) {
        // Don't allow deselecting all sources
        if (next.size > 1) {
          next.delete(source)
        }
      } else {
        next.add(source)
      }
      return next
    })
  }

  // Filter events by active sources
  const filteredEvents = events.filter((event) => {
    // Non-LOG events don't have source, always show them
    if (event.eventType !== 'LOG') return true
    // Default to TASK if no source specified (backward compatibility)
    const source = event.source || 'TASK'
    return activeSources.has(source)
  })

  if (isLoading) {
    return (
      <div className={cn('flex items-center justify-center py-8', className)}>
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className={cn('flex flex-col h-full', className)}>
      {/* Toolbar */}
      <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30">
        <div className="flex items-center gap-2">
          {isLive && (
            <>
              {connected ? (
                <Badge variant="outline" className="text-xs bg-green-500/10 text-green-500 border-green-500/30">
                  <span className="w-1.5 h-1.5 rounded-full bg-green-500 mr-1.5 animate-pulse" />
                  Live
                </Badge>
              ) : reconnecting ? (
                <Badge variant="outline" className="text-xs bg-yellow-500/10 text-yellow-500 border-yellow-500/30">
                  <Loader2 className="h-3 w-3 mr-1.5 animate-spin" />
                  Reconnecting
                </Badge>
              ) : (
                <Badge variant="outline" className="text-xs bg-gray-500/10 text-gray-500 border-gray-500/30">
                  Disconnected
                </Badge>
              )}
            </>
          )}
          <span className="text-xs text-muted-foreground">
            {filteredEvents.length}/{events.length} events
          </span>
        </div>

        <div className="flex items-center gap-1">
          {/* Source filters */}
          {(['TASK', 'AGENT', 'SYSTEM'] as LogSource[]).map((source) => {
            const config = sourceBadgeConfig[source]
            const Icon = config.icon
            const isActive = activeSources.has(source)
            return (
              <Button
                key={source}
                variant="ghost"
                size="sm"
                className={cn(
                  'h-7 px-2 text-xs gap-1',
                  isActive ? config.color : 'text-muted-foreground opacity-50'
                )}
                onClick={() => toggleSource(source)}
                title={`${isActive ? 'Hide' : 'Show'} ${config.label} logs`}
              >
                <Icon className="h-3 w-3" />
                {config.label}
              </Button>
            )
          })}
          
          <div className="w-px h-4 bg-border mx-1" />
          
          {isLive && (
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={() => setIsPaused(!isPaused)}
              title={isPaused ? 'Resume live updates' : 'Pause live updates'}
            >
              {isPaused ? (
                <Play className="h-3.5 w-3.5" />
              ) : (
                <Pause className="h-3.5 w-3.5" />
              )}
            </Button>
          )}
        </div>
      </div>

      {/* Log content */}
      <div ref={scrollRef} className="flex-1 overflow-auto">
        <div className="font-mono text-xs p-2 space-y-0.5">
          {events.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              <Terminal className="h-8 w-8 mx-auto mb-2 opacity-50" />
              <p>No events yet</p>
              {isLive && <p className="text-xs mt-1">Waiting for execution...</p>}
            </div>
          ) : filteredEvents.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              <Terminal className="h-8 w-8 mx-auto mb-2 opacity-50" />
              <p>No events match filter</p>
              <p className="text-xs mt-1">Try enabling more source types</p>
            </div>
          ) : (
            filteredEvents.map((event) => (
              <LogEntry
                key={event.id}
                event={event}
                isExpanded={expandedEvents.has(event.id)}
                onToggle={() => toggleExpanded(event.id)}
                formatTime={formatTime}
              />
            ))
          )}
        </div>
      </div>
    </div>
  )
}

interface LogEntryProps {
  event: ExecutionEvent
  isExpanded: boolean
  onToggle: () => void
  formatTime: (timestamp: string) => string
}

function LogEntry({ event, isExpanded, onToggle, formatTime }: LogEntryProps) {
  const hasData = event.data && Object.keys(event.data).length > 0
  
  // Get source badge config (only for LOG events)
  const source = event.eventType === 'LOG' ? (event.source || 'TASK') : null
  const sourceConfig = source ? sourceBadgeConfig[source] : null
  const SourceIcon = sourceConfig?.icon

  const renderEventContent = () => {
    switch (event.eventType) {
      case 'LOG':
        return (
          <span className={cn(logLevelColors[event.logLevel || 'INFO'])}>
            {event.message}
          </span>
        )

      case 'PROGRESS':
        return (
          <span className="text-cyan-400">
            Progress: {event.progress}%{event.message && ` - ${event.message}`}
          </span>
        )

      case 'TOOL_CALL':
        return (
          <span className="text-purple-400">
            <Wrench className="h-3 w-3 inline mr-1" />
            Calling tool: <span className="font-semibold">{event.toolName}</span>
          </span>
        )

      case 'TOOL_RESULT':
        return (
          <span className={event.isError ? 'text-red-400' : 'text-green-400'}>
            {event.isError ? (
              <AlertCircle className="h-3 w-3 inline mr-1" />
            ) : (
              <CheckCircle2 className="h-3 w-3 inline mr-1" />
            )}
            Tool {event.toolName || event.toolCallId} {event.isError ? 'failed' : 'completed'}
            {event.durationMs && ` (${event.durationMs}ms)`}
            {event.isError && event.message && `: ${event.message}`}
          </span>
        )

      case 'STATUS_CHANGE':
        return (
          <span className="text-orange-400">
            Status changed: {event.message}
            {event.data && (
              <span className="text-muted-foreground">
                {' '}({(event.data as Record<string, string>).fromStatus} → {(event.data as Record<string, string>).toStatus})
              </span>
            )}
          </span>
        )

      default:
        return <span>{event.message || JSON.stringify(event)}</span>
    }
  }

  return (
    <div className="group">
      <div
        className={cn(
          'flex items-start gap-2 py-0.5 px-1 rounded hover:bg-muted/50 cursor-pointer',
          event.eventType === 'LOG' && event.logLevel === 'ERROR' && 'bg-red-500/5'
        )}
        onClick={hasData ? onToggle : undefined}
      >
        {/* Timestamp */}
        <span className="text-muted-foreground shrink-0 tabular-nums">
          {formatTime(event.createdAt)}
        </span>

        {/* Level/Type badge */}
        {event.eventType === 'LOG' && event.logLevel && (
          <Badge
            className={cn(
              'text-[10px] px-1 py-0 h-4 font-normal shrink-0',
              logLevelBadgeColors[event.logLevel]
            )}
          >
            {event.logLevel}
          </Badge>
        )}

        {/* Source badge (only for LOG events) */}
        {sourceConfig && SourceIcon && (
          <Badge
            variant="outline"
            className={cn(
              'text-[9px] px-1 py-0 h-4 font-normal shrink-0 gap-0.5',
              sourceConfig.color
            )}
          >
            <SourceIcon className="h-2.5 w-2.5" />
            {sourceConfig.label}
          </Badge>
        )}

        {/* Expand indicator */}
        {hasData && (
          <span className="text-muted-foreground shrink-0">
            {isExpanded ? (
              <ChevronDown className="h-3 w-3" />
            ) : (
              <ChevronRight className="h-3 w-3" />
            )}
          </span>
        )}

        {/* Content */}
        <span className="break-all leading-relaxed">{renderEventContent()}</span>
      </div>

      {/* Expanded data */}
      {isExpanded && hasData && (
        <pre className="ml-20 p-2 bg-muted/30 rounded text-[10px] overflow-x-auto">
          {JSON.stringify(event.data, null, 2)}
        </pre>
      )}
    </div>
  )
}
