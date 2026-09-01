// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { AlertCircle, Play, Square } from 'lucide-react'
import type { WorkflowTask } from '@/lib/api'

interface TaskPauseCardProps {
  task: WorkflowTask
  onContinue: (taskId: string) => Promise<void>
  onStop: (taskId: string, reason: string) => Promise<void>
}

/**
 * Pause review card for paused workflow tasks (J3 pause gate).
 *
 * Shows when a task has `status === 'PAUSED'`, displaying the pause
 * reason and Continue/Stop buttons. Reusable for both the `pauseMode`
 * path and the future execution-approval path.
 */
export function TaskPauseCard({ task, onContinue, onStop }: TaskPauseCardProps) {
  const [loading, setLoading] = useState<'continue' | 'stop' | null>(null)

  const isPausedBefore = task.pauseState === 'PAUSED_BEFORE'
  const isPausedAfter = task.pauseState === 'PAUSED_AFTER'

  const label = isPausedBefore
    ? 'Review before execution'
    : isPausedAfter
      ? 'Review after execution'
      : 'Approval required'

  const handleContinue = async () => {
    setLoading('continue')
    try {
      await onContinue(task.id)
    } finally {
      setLoading(null)
    }
  }

  const handleStop = async () => {
    setLoading('stop')
    try {
      await onStop(task.id, 'Stopped by operator from pause card')
    } finally {
      setLoading(null)
    }
  }

  return (
    <Card
      className="border-amber-300 bg-amber-50"
      data-testid="pause-review-card"
    >
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm">
          <AlertCircle className="h-4 w-4 text-amber-600" />
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="text-sm">
          <p className="font-medium">Step: {task.stepId}</p>
          {task.pauseReason && (
            <p className="text-muted-foreground mt-1">{task.pauseReason}</p>
          )}
          {isPausedAfter && task.output && (
            <div className="mt-2 p-2 bg-white rounded border text-xs max-h-40 overflow-y-auto">
              <pre className="whitespace-pre-wrap break-all">
                {JSON.stringify(task.output, null, 2)}
              </pre>
            </div>
          )}
        </div>
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="default"
            onClick={handleContinue}
            disabled={loading !== null}
            className="flex items-center gap-1"
            data-testid="pause-continue-button"
          >
            <Play className="h-3 w-3" />
            {loading === 'continue' ? 'Continuing...' : 'Continue'}
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={handleStop}
            disabled={loading !== null}
            className="flex items-center gap-1"
            data-testid="pause-stop-button"
          >
            <Square className="h-3 w-3" />
            {loading === 'stop' ? 'Stopping...' : 'Stop'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}