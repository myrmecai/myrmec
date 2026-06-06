import { memo } from 'react'
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react'
import { Bot, Play, CheckCircle2, XCircle, Clock, AlertCircle } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'

export interface StepNodeData extends Record<string, unknown> {
  id: string
  name: string
  agentProfileId: string
  agentProfileName?: string
  prompt?: string
  dependsOn?: string[]
  transitions?: Record<string, string>
  timeoutSeconds?: number
  maxRetries?: number
  // Runtime info (for request view)
  status?: 'pending' | 'ready' | 'running' | 'completed' | 'cancelled'
  result?: 'success' | 'failure' | 'timeout'
  // Authoring-time validation flag
  invalid?: boolean
}

export type StepNodeType = Node<StepNodeData, 'step'>

const statusColors: Record<string, string> = {
  pending: 'border-gray-300 bg-gray-50',
  ready: 'border-blue-300 bg-blue-50',
  running: 'border-yellow-300 bg-yellow-50 animate-pulse',
  completed: 'border-green-300 bg-green-50',
  cancelled: 'border-red-300 bg-red-50',
}

const statusIcons: Record<string, React.ReactNode> = {
  pending: <Clock className="h-4 w-4 text-gray-500" />,
  ready: <Play className="h-4 w-4 text-blue-500" />,
  running: <AlertCircle className="h-4 w-4 text-yellow-600" />,
  completed: <CheckCircle2 className="h-4 w-4 text-green-600" />,
  cancelled: <XCircle className="h-4 w-4 text-red-500" />,
}

const resultBadges: Record<string, { color: string; label: string }> = {
  success: { color: 'bg-green-600', label: 'Success' },
  failure: { color: 'bg-red-600', label: 'Failed' },
  timeout: { color: 'bg-yellow-600', label: 'Timeout' },
}

function StepNodeComponent({ data, selected }: NodeProps<StepNodeType>) {
  const status = data.status || 'pending'
  const hasResult = data.result && resultBadges[data.result]

  return (
    <div
      className={cn(
        'rounded-lg border-2 shadow-sm min-w-[180px] max-w-[250px]',
        'transition-all duration-200',
        statusColors[status] || 'border-gray-200 bg-white',
        data.invalid && 'border-red-500 bg-red-50',
        selected && 'ring-2 ring-blue-500 ring-offset-2'
      )}
    >
      {/* Input handle (top) */}
      <Handle
        type="target"
        position={Position.Top}
        className="!w-3 !h-3 !bg-gray-400 !border-2 !border-white"
      />

      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-gray-200/50">
        {data.status ? statusIcons[status] : <Bot className="h-4 w-4 text-muted-foreground" />}
        <span className="font-medium text-sm truncate flex-1">{data.name}</span>
        {data.invalid && (
          <AlertCircle
            className="h-4 w-4 text-red-600"
            aria-label="Step has validation errors"
          />
        )}
        {hasResult && (
          <Badge className={cn('text-xs', resultBadges[data.result!].color)}>
            {resultBadges[data.result!].label}
          </Badge>
        )}
      </div>

      {/* Body */}
      <div className="px-3 py-2 space-y-1">
        <div className="text-xs text-muted-foreground truncate">
          {data.agentProfileName || 'No profile selected'}
        </div>
        {data.prompt && (
          <div className="text-xs text-muted-foreground/70 truncate">
            {data.prompt.substring(0, 50)}...
          </div>
        )}
        {data.timeoutSeconds && (
          <div className="text-xs text-muted-foreground">
            Timeout: {data.timeoutSeconds}s
          </div>
        )}
      </div>

      {/* Output handles (bottom) - SUCCESS, FAILURE, TIMEOUT */}
      <Handle
        type="source"
        position={Position.Bottom}
        id="success"
        className="!w-3 !h-3 !bg-green-500 !border-2 !border-white !-bottom-1.5 !left-[25%]"
        title="Success"
      />
      <Handle
        type="source"
        position={Position.Bottom}
        id="failure"
        className="!w-3 !h-3 !bg-red-500 !border-2 !border-white !-bottom-1.5 !left-[50%]"
        title="Failure"
      />
      <Handle
        type="source"
        position={Position.Bottom}
        id="timeout"
        className="!w-3 !h-3 !bg-yellow-500 !border-2 !border-white !-bottom-1.5 !left-[75%]"
        title="Timeout"
      />
    </div>
  )
}

export const StepNode = memo(StepNodeComponent)
