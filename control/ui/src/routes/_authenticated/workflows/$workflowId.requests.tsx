import { createFileRoute, useNavigate, Link, Outlet, useMatch } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  workflowsApi,
  type RequestStatus,
} from '@/lib/api'
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
  Play,
  XCircle,
  Eye,
  AlertCircle,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { useState } from 'react'

export const Route = createFileRoute('/_authenticated/workflows/$workflowId/requests')({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: WorkflowRequestsLayout,
})

// Layout component that handles child routing
function WorkflowRequestsLayout() {
  // Check if we're on a child route (request detail page)
  const childMatch = useMatch({
    from: '/_authenticated/workflows/$workflowId/requests/$requestId',
    shouldThrow: false,
  })
  
  // If on a child route, render the child via Outlet
  if (childMatch) {
    return <Outlet />
  }
  
  // Otherwise render the list
  return <WorkflowRequestsList />
}

const statusColors: Record<RequestStatus, string> = {
  PENDING: 'bg-gray-500',
  RUNNING: 'bg-blue-500',
  COMPLETED: 'bg-green-600',
  FAILED: 'bg-red-600',
  CANCELLED: 'bg-yellow-600',
  TIMEOUT: 'bg-orange-600',
}

const statusLabels: Record<RequestStatus, string> = {
  PENDING: 'Pending',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  TIMEOUT: 'Timeout',
}

function WorkflowRequestsList() {
  const { workflowId } = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { projectId } = Route.useSearch()
  
  const [startOpen, setStartOpen] = useState(false)

  // Load workflow info
  const { data: workflow, isLoading: workflowLoading } = useQuery({
    queryKey: ['workflow', projectId, workflowId],
    queryFn: () => workflowsApi.get(projectId!, workflowId),
    enabled: !!projectId,
  })

  // Load requests
  const { data: requests, isLoading: requestsLoading, error } = useQuery({
    queryKey: ['workflow-requests', projectId, workflowId],
    queryFn: () => workflowsApi.listRequests(projectId!, workflowId),
    enabled: !!projectId,
    refetchInterval: 5000, // Poll every 5 seconds for running requests
  })

  const startMutation = useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      workflowsApi.startRequest(projectId!, workflowId, {
        workflowId,
        input,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow-requests', projectId, workflowId] })
      setStartOpen(false)
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (requestId: string) =>
      workflowsApi.cancelRequest(projectId!, workflowId, requestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow-requests', projectId, workflowId] })
    },
  })

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

  const isLoading = workflowLoading || requestsLoading

  if (isLoading) {
    return (
      <div className="container mx-auto py-6">
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  if (error || !workflow) {
    return (
      <div className="container mx-auto py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <AlertCircle className="h-12 w-12 mx-auto mb-4 text-red-500" />
            <p className="text-lg font-medium">Failed to load workflow</p>
            <Button className="mt-4" onClick={() => navigate({ to: '/workflows' })}>
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Workflows
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  const canStart = workflow.status === 'PUBLISHED'

  return (
    <div className="container mx-auto py-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate({ to: '/workflows' })}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1">
          <h1 className="text-2xl font-semibold">{workflow.name}</h1>
          <p className="text-muted-foreground">
            {workflow.projectName} • v{workflow.version}
          </p>
        </div>
        <Link
          to="/workflows/$workflowId/edit"
          params={{ workflowId }}
          search={{ projectId }}
        >
          <Button variant="outline">Edit Workflow</Button>
        </Link>
        {canStart && (
          <Dialog open={startOpen} onOpenChange={setStartOpen}>
            <DialogTrigger asChild>
              <Button>
                <Play className="h-4 w-4 mr-2" />
                Start Execution
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Start Workflow Execution</DialogTitle>
                <DialogDescription>
                  Start a new execution of "{workflow.name}"
                </DialogDescription>
              </DialogHeader>
              <div className="py-4">
                <p className="text-sm text-muted-foreground">
                  This will create a new workflow request in PENDING state.
                  The execution engine will process it when agents are available.
                </p>
                {workflow.inputSchema && (
                  <p className="text-sm text-yellow-600 mt-2">
                    Note: This workflow has an input schema. Input form will be added in a future update.
                  </p>
                )}
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setStartOpen(false)}>
                  Cancel
                </Button>
                <Button
                  onClick={() => startMutation.mutate({})}
                  disabled={startMutation.isPending}
                >
                  {startMutation.isPending ? (
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  ) : (
                    <Play className="h-4 w-4 mr-2" />
                  )}
                  Start
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        )}
      </div>

      {/* Requests List */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>Execution Requests</CardTitle>
              <CardDescription>
                History of workflow executions
              </CardDescription>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                queryClient.invalidateQueries({
                  queryKey: ['workflow-requests', projectId, workflowId],
                })
              }
            >
              <RefreshCw className="h-4 w-4" />
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {!requests || requests.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground">
              <Play className="h-12 w-12 mx-auto mb-4 opacity-50" />
              <p>No executions yet</p>
              {canStart && (
                <p className="text-sm">Start a new execution to see results here</p>
              )}
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Request ID</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead>Started</TableHead>
                  <TableHead>Completed</TableHead>
                  <TableHead>Created By</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {requests.map((request) => (
                  <TableRow key={request.id}>
                    <TableCell className="font-mono text-sm">
                      {request.id.substring(0, 8)}...
                    </TableCell>
                    <TableCell>v{request.workflowVersion}</TableCell>
                    <TableCell>
                      <Badge className={statusColors[request.status]}>
                        {statusLabels[request.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      {new Date(request.createdAt).toLocaleString()}
                    </TableCell>
                    <TableCell>
                      {request.startedAt
                        ? new Date(request.startedAt).toLocaleString()
                        : '-'}
                    </TableCell>
                    <TableCell>
                      {request.completedAt
                        ? new Date(request.completedAt).toLocaleString()
                        : '-'}
                    </TableCell>
                    <TableCell>{request.createdByEmail}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-2">
                        <Link
                          to="/workflows/$workflowId/requests/$requestId"
                          params={{ workflowId, requestId: request.id }}
                          search={{ projectId }}
                        >
                          <Button variant="outline" size="sm">
                            <Eye className="h-4 w-4" />
                          </Button>
                        </Link>
                        {(request.status === 'PENDING' ||
                          request.status === 'RUNNING') && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => cancelMutation.mutate(request.id)}
                            disabled={cancelMutation.isPending}
                          >
                            <XCircle className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
