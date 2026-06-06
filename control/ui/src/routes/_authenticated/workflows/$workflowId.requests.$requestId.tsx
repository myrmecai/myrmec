import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import {
  workflowsApi,
  type TaskStatus,
  type TaskResult,
  type WorkflowTask,
} from '@/lib/api'
import {
  WorkflowCanvas,
  LogViewer,
  StepRunPanel,
  type StepRunAction,
} from '@/components/workflow'
import { useAgentProfiles } from '@/lib/use-agent-profiles'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import {
  ArrowLeft,
  AlertCircle,
  Loader2,
  RefreshCw,
  CheckCircle2,
  XCircle,
  Clock,
  Play,
  FileText,
  X,
} from 'lucide-react'
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@/components/ui/tabs'

export const Route = createFileRoute(
  '/_authenticated/workflows/$workflowId/requests/$requestId'
)({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: RequestDetailPage,
})

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

const statusIcons: Record<string, React.ReactNode> = {
  PENDING: <Clock className="h-4 w-4" />,
  READY: <Play className="h-4 w-4" />,
  RUNNING: <Loader2 className="h-4 w-4 animate-spin" />,
  COMPLETED: <CheckCircle2 className="h-4 w-4" />,
  CANCELLED: <XCircle className="h-4 w-4" />,
}

function RequestDetailPage() {
  const { workflowId, requestId } = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { projectId } = Route.useSearch()

  // UI state: step selection on canvas, active tab, logs task filter
  const [selectedStepId, setSelectedStepId] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<string>('canvas')
  const [logsTaskFilter, setLogsTaskFilter] = useState<{
    taskId: string
    stepName: string
  } | null>(null)

  // Load workflow for canvas display
  const { data: workflow } = useQuery({
    queryKey: ['workflow', projectId, workflowId],
    queryFn: () => workflowsApi.get(projectId!, workflowId),
    enabled: !!projectId,
  })

  // Load request
  const { data: request, isLoading: requestLoading } = useQuery({
    queryKey: ['workflow-request', projectId, workflowId, requestId],
    queryFn: () => workflowsApi.getRequest(projectId!, workflowId, requestId),
    enabled: !!projectId,
    refetchInterval: (query) =>
      query.state.data?.status === 'RUNNING' || query.state.data?.status === 'PENDING' ? 3000 : false,
  })

  // Load tasks
  const { data: tasks, isLoading: tasksLoading } = useQuery({
    queryKey: ['workflow-tasks', projectId, workflowId, requestId],
    queryFn: () => workflowsApi.listTasks(projectId!, workflowId, requestId),
    enabled: !!projectId,
    refetchInterval: (query) => {
      const data = query.state.data as WorkflowTask[] | undefined
      return data?.some((t: WorkflowTask) => t.status === 'RUNNING' || t.status === 'READY')
        ? 3000
        : false
    },
  })

  // Merge task status into workflow steps for canvas visualization
  const stepsWithStatus = useMemo(() => {
    if (!workflow?.steps || !tasks) return workflow?.steps || []

    const taskMap = new Map(tasks.map((t) => [t.stepId, t]))
    return workflow.steps.map((step) => {
      const task = taskMap.get(step.id)
      return {
        ...step,
        status: task?.status?.toLowerCase() as 'pending' | 'ready' | 'running' | 'completed' | 'cancelled' | undefined,
        result: task?.result?.toLowerCase() as 'success' | 'failure' | 'timeout' | undefined,
      }
    })
  }, [workflow?.steps, tasks])

  // Agent profiles for step detail panel
  const { data: profiles } = useAgentProfiles()

  // Selected step + corresponding task
  const selectedStep = useMemo(
    () => workflow?.steps?.find((s) => s.id === selectedStepId) ?? null,
    [workflow?.steps, selectedStepId]
  )
  const selectedTask = useMemo(
    () => tasks?.find((t) => t.stepId === selectedStepId) ?? null,
    [tasks, selectedStepId]
  )
  const selectedAgentProfile = useMemo(
    () => profiles?.find((p) => p.id === selectedStep?.agentProfileId),
    [profiles, selectedStep?.agentProfileId]
  )

  // Action row for the step detail panel. New actions are added here as
  // additional entries (token usage, generated output, retry, etc.).
  const stepActions: StepRunAction[] = useMemo(() => {
    if (!selectedStep) return []
    return [
      {
        id: 'logs',
        label: 'View Logs',
        icon: <FileText className="h-4 w-4" />,
        enabled: !!selectedTask,
        disabledReason: "Step hasn't started yet",
        onClick: () => {
          if (!selectedTask) return
          setLogsTaskFilter({
            taskId: selectedTask.id,
            stepName: selectedStep.name,
          })
          setActiveTab('logs')
        },
      },
    ]
  }, [selectedStep, selectedTask])

  if (!projectId) {
    return (
      <div className="container mx-auto py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <AlertCircle className="h-12 w-12 mx-auto mb-4 text-red-500" />
            <p className="text-lg font-medium">Project ID is required</p>
            <Button className="mt-4" onClick={() => navigate({ to: '/workflows' })}>
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Workflows
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  const isLoading = requestLoading || tasksLoading

  if (isLoading) {
    return (
      <div className="container mx-auto py-6">
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  if (!request) {
    return (
      <div className="container mx-auto py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <AlertCircle className="h-12 w-12 mx-auto mb-4 text-red-500" />
            <p className="text-lg font-medium">Request not found</p>
            <Button
              className="mt-4"
              onClick={() =>
                navigate({
                  to: '/workflows/$workflowId/requests',
                  params: { workflowId },
                  search: { projectId },
                })
              }
            >
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Requests
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="container mx-auto py-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={() =>
            navigate({
              to: '/workflows/$workflowId/requests',
              params: { workflowId },
              search: { projectId },
            })
          }
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1">
          <h1 className="text-2xl font-semibold">{request.workflowName}</h1>
          <p className="text-muted-foreground font-mono text-sm">
            Request: {request.id}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {statusIcons[request.status]}
          <Badge
            className={
              request.status === 'COMPLETED'
                ? 'bg-green-600'
                : request.status === 'FAILED'
                  ? 'bg-red-600'
                  : request.status === 'RUNNING'
                    ? 'bg-blue-500'
                    : 'bg-gray-500'
            }
          >
            {request.status}
          </Badge>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            queryClient.invalidateQueries({
              queryKey: ['workflow-request', projectId, workflowId, requestId],
            })
            queryClient.invalidateQueries({
              queryKey: ['workflow-tasks', projectId, workflowId, requestId],
            })
          }}
        >
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      {/* Tabs */}
      <Tabs
        value={activeTab}
        onValueChange={setActiveTab}
        className="space-y-4"
      >
        <TabsList>
          <TabsTrigger value="canvas">Visual</TabsTrigger>
          <TabsTrigger value="tasks">Tasks ({tasks?.length || 0})</TabsTrigger>
          <TabsTrigger value="logs">Logs</TabsTrigger>
          <TabsTrigger value="io">Input/Output</TabsTrigger>
        </TabsList>

        {/* Canvas View */}
        <TabsContent value="canvas" className="space-y-0">
          <div className="flex gap-4 h-[500px]">
            <div className="flex-1 border rounded-lg overflow-hidden">
              {workflow && (
                <WorkflowCanvas
                  steps={stepsWithStatus}
                  onStepsChange={() => {}}
                  onStepSelect={setSelectedStepId}
                  selectedStepId={selectedStepId}
                  readOnly
                  agentProfiles={profiles || []}
                />
              )}
            </div>
            <div className="w-80 shrink-0">
              <StepRunPanel
                step={selectedStep}
                task={selectedTask}
                agentProfile={selectedAgentProfile}
                actions={stepActions}
                onClose={() => setSelectedStepId(null)}
              />
            </div>
          </div>
        </TabsContent>

        {/* Tasks Table */}
        <TabsContent value="tasks">
          <Card>
            <CardHeader>
              <CardTitle>Task Executions</CardTitle>
              <CardDescription>
                Individual step executions for this request
              </CardDescription>
            </CardHeader>
            <CardContent>
              {!tasks || tasks.length === 0 ? (
                <div className="text-center py-12 text-muted-foreground">
                  <Clock className="h-12 w-12 mx-auto mb-4 opacity-50" />
                  <p>No tasks created yet</p>
                  <p className="text-sm">
                    Tasks will appear here once the execution engine processes this request
                  </p>
                </div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Step</TableHead>
                      <TableHead>Agent Profile</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Result</TableHead>
                      <TableHead>Attempt</TableHead>
                      <TableHead className="text-right">Tokens</TableHead>
                      <TableHead className="text-right">Cost</TableHead>
                      <TableHead>Started</TableHead>
                      <TableHead>Completed</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {tasks.map((task) => (
                      <TableRow key={task.id}>
                        <TableCell className="font-medium">
                          {task.stepId}
                        </TableCell>
                        <TableCell>{task.agentProfileName}</TableCell>
                        <TableCell>
                          <Badge className={taskStatusColors[task.status]}>
                            {taskStatusLabels[task.status]}
                          </Badge>
                        </TableCell>
                        <TableCell>
                          {task.result ? (
                            <Badge className={taskResultColors[task.result]}>
                              {taskResultLabels[task.result]}
                            </Badge>
                          ) : (
                            '-'
                          )}
                        </TableCell>
                        <TableCell>{task.attempt}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          {task.metrics?.totalTokens != null
                            ? task.metrics.totalTokens.toLocaleString()
                            : '-'}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {task.metrics?.costUsd != null
                            ? `${task.metrics.currency ?? 'USD'} ${(
                                task.metrics.costUsd < 0.01
                                  ? task.metrics.costUsd.toFixed(6)
                                  : task.metrics.costUsd.toFixed(4)
                              )}`
                            : '-'}
                        </TableCell>
                        <TableCell>
                          {task.startedAt
                            ? new Date(task.startedAt).toLocaleString()
                            : '-'}
                        </TableCell>
                        <TableCell>
                          {task.completedAt
                            ? new Date(task.completedAt).toLocaleString()
                            : '-'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Logs */}
        <TabsContent value="logs">
          <Card>
            <CardHeader>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <CardTitle>Execution Logs</CardTitle>
                  <CardDescription>
                    Real-time logs from agent execution
                  </CardDescription>
                </div>
                {logsTaskFilter && (
                  <Badge
                    variant="outline"
                    className="flex items-center gap-1 pr-1"
                  >
                    <span>Filtered to: {logsTaskFilter.stepName}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-5 w-5 p-0 ml-1"
                      onClick={() => setLogsTaskFilter(null)}
                      aria-label="Clear log filter"
                    >
                      <X className="h-3 w-3" />
                    </Button>
                  </Badge>
                )}
              </div>
            </CardHeader>
            <CardContent className="p-0">
              <LogViewer
                projectId={projectId}
                workflowId={workflowId}
                requestId={requestId}
                taskId={logsTaskFilter?.taskId}
                isLive={request.status === 'RUNNING' || request.status === 'PENDING'}
                className="h-[400px]"
              />
            </CardContent>
          </Card>
        </TabsContent>

        {/* Input/Output */}
        <TabsContent value="io">
          <div className="grid md:grid-cols-2 gap-4">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Input</CardTitle>
              </CardHeader>
              <CardContent>
                <pre className="text-xs bg-muted p-3 rounded-md overflow-auto max-h-[300px]">
                  {request.input
                    ? JSON.stringify(request.input, null, 2)
                    : 'No input provided'}
                </pre>
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Output</CardTitle>
              </CardHeader>
              <CardContent>
                <pre className="text-xs bg-muted p-3 rounded-md overflow-auto max-h-[300px]">
                  {request.output
                    ? JSON.stringify(request.output, null, 2)
                    : 'No output yet'}
                </pre>
                {request.errorMessage && (
                  <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded-md">
                    <p className="text-sm font-medium text-red-800">Error</p>
                    <p className="text-sm text-red-600">{request.errorMessage}</p>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  )
}
