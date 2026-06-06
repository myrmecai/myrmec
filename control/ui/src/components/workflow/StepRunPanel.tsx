import type { ReactNode } from 'react'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { X } from 'lucide-react'
import type {
  AgentProfile,
  TaskResult,
  TaskStatus,
  WorkflowStep,
  WorkflowTask,
} from '@/lib/api'

/**
 * Action shown in the StepRunPanel action row. The list is built by the parent
 * route so each action can reach into tab state, mutation handlers, etc. Adding
 * a new action (e.g. token usage, generated output, retry) is a one-line
 * extension on the consumer side.
 */
export interface StepRunAction {
  id: string
  label: string
  icon?: ReactNode
  enabled: boolean
  disabledReason?: string
  onClick: () => void
}

interface StepRunPanelProps {
  step: WorkflowStep | null
  task: WorkflowTask | null
  agentProfile?: AgentProfile
  actions: StepRunAction[]
  onClose: () => void
}

const taskStatusColors: Record<TaskStatus, string> = {
  PENDING: 'bg-gray-500',
  READY: 'bg-blue-500',
  RUNNING: 'bg-yellow-500',
  COMPLETED: 'bg-green-600',
  CANCELLED: 'bg-red-600',
}

const taskStatusLabels: Record<TaskStatus, string> = {
  PENDING: 'Pending',
  READY: 'Ready',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
}

const taskResultColors: Record<TaskResult, string> = {
  SUCCESS: 'bg-green-600',
  FAILURE: 'bg-red-600',
  TIMEOUT: 'bg-orange-600',
}

const taskResultLabels: Record<TaskResult, string> = {
  SUCCESS: 'Success',
  FAILURE: 'Failed',
  TIMEOUT: 'Timeout',
}

function formatDuration(startedAt: string | null, completedAt: string | null): string {
  if (!startedAt) return '-'
  const start = new Date(startedAt).getTime()
  const end = completedAt ? new Date(completedAt).getTime() : Date.now()
  const ms = end - start
  if (ms < 1000) return `${ms} ms`
  const secs = Math.floor(ms / 1000)
  if (secs < 60) return `${secs}s`
  const mins = Math.floor(secs / 60)
  const remSecs = secs % 60
  return `${mins}m ${remSecs}s`
}

function formatMillis(ms: number | null | undefined): string {
  if (ms == null) return '\u2014'
  if (ms < 1000) return `${ms} ms`
  const secs = ms / 1000
  if (secs < 60) return `${secs.toFixed(1)}s`
  const mins = Math.floor(secs / 60)
  const remSecs = Math.round(secs % 60)
  return `${mins}m ${remSecs}s`
}

function formatTokens(n: number | null | undefined): string {
  if (n == null) return '\u2014'
  return n.toLocaleString()
}

function formatCost(cost: number | null | undefined, currency: string | null | undefined): string {
  if (cost == null) return '\u2014'
  const cur = currency ?? 'USD'
  // Below 1 cent: show up to 6 decimals so micro-costs stay visible.
  const digits = cost < 0.01 ? 6 : 4
  return `${cur} ${cost.toFixed(digits)}`
}

export function StepRunPanel({
  step,
  task,
  agentProfile,
  actions,
  onClose,
}: StepRunPanelProps) {
  if (!step) {
    return (
      <Card className="h-full">
        <CardHeader>
          <CardTitle className="text-sm">Step Details</CardTitle>
        </CardHeader>
        <CardContent className="text-muted-foreground text-sm">
          Select a step on the canvas to view its details and actions.
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="h-full overflow-hidden flex flex-col">
      <CardHeader className="flex flex-row items-start justify-between space-y-0 pb-3">
        <div className="space-y-1">
          <CardTitle className="text-sm">{step.name}</CardTitle>
          <p className="text-xs text-muted-foreground font-mono">{step.id}</p>
        </div>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 w-7 p-0"
          onClick={onClose}
          aria-label="Close panel"
        >
          <X className="h-4 w-4" />
        </Button>
      </CardHeader>

      <CardContent className="flex-1 overflow-y-auto space-y-4 text-sm">
        {/* Agent profile */}
        <div>
          <p className="text-xs text-muted-foreground mb-1">Agent profile</p>
          <p>{agentProfile?.name ?? task?.agentProfileName ?? '\u2014'}</p>
        </div>

        {/* Status & result */}
        <div className="flex flex-wrap items-center gap-2">
          {task ? (
            <Badge className={taskStatusColors[task.status]}>
              {taskStatusLabels[task.status]}
            </Badge>
          ) : (
            <Badge variant="outline">Not started</Badge>
          )}
          {task?.result && (
            <Badge className={taskResultColors[task.result]}>
              {taskResultLabels[task.result]}
            </Badge>
          )}
          {task && task.attempt > 1 && (
            <Badge variant="outline">Attempt {task.attempt}</Badge>
          )}
        </div>

        {/* Task timing */}
        {task && (
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div>
              <p className="text-muted-foreground">Started</p>
              <p>
                {task.startedAt
                  ? new Date(task.startedAt).toLocaleString()
                  : '\u2014'}
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">Completed</p>
              <p>
                {task.completedAt
                  ? new Date(task.completedAt).toLocaleString()
                  : '\u2014'}
              </p>
            </div>
            <div>
              <p className="text-muted-foreground">Duration</p>
              <p>{formatDuration(task.startedAt, task.completedAt)}</p>
            </div>
            <div>
              <p className="text-muted-foreground">Attempt</p>
              <p>{task.attempt}</p>
            </div>
          </div>
        )}

        {/* Metrics: tokens, cost, model/tool time */}
        {task?.metrics && (
          <div className="rounded-md border bg-muted/30 p-2 text-xs space-y-2">
            <p className="font-medium text-muted-foreground">Run metrics</p>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <p className="text-muted-foreground">Model</p>
                <p className="font-mono break-all">{task.metrics.model ?? '\u2014'}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Cost</p>
                <p>{formatCost(task.metrics.costUsd, task.metrics.currency)}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Prompt tokens</p>
                <p>{formatTokens(task.metrics.promptTokens)}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Completion tokens</p>
                <p>{formatTokens(task.metrics.completionTokens)}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Total tokens</p>
                <p>{formatTokens(task.metrics.totalTokens)}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Model calls</p>
                <p>{task.metrics.modelCallCount ?? '\u2014'}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Model time</p>
                <p>{formatMillis(task.metrics.modelDurationMs)}</p>
              </div>
              <div>
                <p className="text-muted-foreground">Tool time</p>
                <p>{formatMillis(task.metrics.toolDurationMs)}</p>
              </div>
            </div>
          </div>
        )}

        {/* Error message */}
        {task?.errorMessage && (
          <div className="rounded-md border border-red-200 bg-red-50 p-2 text-xs">
            <p className="font-medium text-red-800 mb-1">Error</p>
            <p className="text-red-700 whitespace-pre-wrap break-words">
              {task.errorMessage}
            </p>
          </div>
        )}

        {/* Actions */}
        {actions.length > 0 && (
          <div className="space-y-2 pt-2 border-t">
            <p className="text-xs text-muted-foreground">Actions</p>
            <div className="flex flex-wrap gap-2">
              {actions.map((action) => (
                <Button
                  key={action.id}
                  variant="outline"
                  size="sm"
                  disabled={!action.enabled}
                  title={!action.enabled ? action.disabledReason : undefined}
                  onClick={action.onClick}
                >
                  {action.icon}
                  <span className={action.icon ? 'ml-2' : undefined}>
                    {action.label}
                  </span>
                </Button>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
