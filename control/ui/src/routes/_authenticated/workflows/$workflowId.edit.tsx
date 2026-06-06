import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useCallback, useMemo, useEffect } from 'react'
import {
  workflowsApi,
  type WorkflowStep,
  type UpdateWorkflowRequest,
  type ArtifactsRepo,
} from '@/lib/api'
import { useAgentProfiles } from '@/lib/use-agent-profiles'
import { validateWorkflowSteps } from '@/lib/workflow-schema'
import {
  WorkflowCanvas,
  StepPropertiesPanel,
} from '@/components/workflow'
import { InputSchemaEditor } from '@/components/workflow/InputSchemaEditor'
import { ArtifactsRepoSection } from '@/components/workflow/ArtifactsRepoSection'
import { RunWorkflowDialog } from '@/components/workflow/RunWorkflowDialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Card,
  CardContent,
} from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import {
  ArrowLeft,
  Save,
  Play,
  Archive,
  AlertCircle,
  Loader2,
  ChevronUp,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'

export const Route = createFileRoute('/_authenticated/workflows/$workflowId/edit')({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: search.projectId as string | undefined,
  }),
  component: WorkflowEditorPage,
})

const statusColors: Record<string, string> = {
  DRAFT: 'bg-gray-500',
  PUBLISHED: 'bg-green-600',
  DISABLED: 'bg-yellow-600',
  ARCHIVED: 'bg-red-600',
}

function WorkflowEditorPage() {
  const { workflowId } = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [selectedStepId, setSelectedStepId] = useState<string | null>(null)
  const [localSteps, setLocalSteps] = useState<WorkflowStep[]>([])
  const [localName, setLocalName] = useState('')
  const [localInputSchema, setLocalInputSchema] = useState<
    Record<string, unknown> | null
  >(null)
  const [localArtifactsRepo, setLocalArtifactsRepo] =
    useState<ArtifactsRepo | null>(null)
  const [hasChanges, setHasChanges] = useState(false)
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [openSection, setOpenSection] = useState<
    'steps' | 'inputs' | 'artifacts'
  >('steps')

  const { projectId } = Route.useSearch()

  // Load workflow
  const { data: workflow, isLoading, error } = useQuery({
    queryKey: ['workflow', projectId, workflowId],
    queryFn: () => workflowsApi.get(projectId!, workflowId),
    enabled: !!projectId,
  })

  // Initialize local state when workflow loads or version changes (after save).
  // Do NOT depend on the workflow object reference — query refetches would otherwise
  // wipe in-progress edits.
  useEffect(() => {
    if (workflow) {
      setLocalSteps(workflow.steps)
      setLocalName(workflow.name)
      setLocalInputSchema(workflow.inputSchema ?? null)
      setLocalArtifactsRepo(workflow.artifactsRepo ?? null)
      setHasChanges(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflow?.id, workflow?.version])

  // Load agent profiles for step configuration (cached, deduped across views)
  const { data: profiles } = useAgentProfiles()

  const [saveError, setSaveError] = useState<string | null>(null)

  const updateMutation = useMutation({
    mutationFn: (data: UpdateWorkflowRequest) =>
      workflowsApi.update(projectId!, workflowId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow', projectId, workflowId] })
      queryClient.invalidateQueries({ queryKey: ['workflows', projectId] })
      setHasChanges(false)
      setSaveError(null)
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Failed to save workflow'
      setSaveError(msg)
    },
  })

  const publishMutation = useMutation({
    mutationFn: () => workflowsApi.publish(projectId!, workflowId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow', projectId, workflowId] })
      queryClient.invalidateQueries({ queryKey: ['workflows', projectId] })
    },
  })

  const archiveMutation = useMutation({
    mutationFn: () => workflowsApi.archive(projectId!, workflowId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow', projectId, workflowId] })
      queryClient.invalidateQueries({ queryKey: ['workflows', projectId] })
    },
  })

  const handleStepsChange = useCallback((steps: WorkflowStep[]) => {
    setLocalSteps(steps)
    setHasChanges(true)
  }, [])

  const handleStepUpdate = useCallback((updatedStep: WorkflowStep) => {
    setLocalSteps((prev) =>
      prev.map((s) => (s.id === updatedStep.id ? updatedStep : s))
    )
    setHasChanges(true)
  }, [])

  const handleStepsBulkUpdate = useCallback((next: WorkflowStep[]) => {
    setLocalSteps(next)
    setHasChanges(true)
  }, [])

  const handleStepDelete = useCallback((stepId: string) => {
    setLocalSteps((prev) => prev.filter((s) => s.id !== stepId))
    setSelectedStepId(null)
    setHasChanges(true)
  }, [])

  const handleSave = useCallback(() => {
    if (!workflow) return
    // Omit artifactsRepo entirely when no URL is configured (backend requires url when present)
    const artifactsRepo =
      localArtifactsRepo && localArtifactsRepo.url && localArtifactsRepo.url.trim()
        ? localArtifactsRepo
        : undefined
    updateMutation.mutate({
      name: localName,
      description: workflow.description || undefined,
      steps: localSteps,
      inputSchema: localInputSchema || undefined,
      artifactsRepo,
      status: workflow.status,
    })
  }, [workflow, localName, localSteps, localInputSchema, localArtifactsRepo, updateMutation])

  const selectedStep = useMemo(
    () => localSteps.find((s) => s.id === selectedStepId) || null,
    [localSteps, selectedStepId]
  )

  const validation = useMemo(
    () => validateWorkflowSteps(localSteps),
    [localSteps]
  )
  const invalidCount = validation.invalidStepIds.size

  const profilesForCanvas = useMemo(
    () =>
      profiles?.map((p) => ({ id: p.id, name: p.name })) || [],
    [profiles]
  )

  if (!projectId) {
    return (
      <div className="container mx-auto py-6">
        <Card>
          <CardContent className="py-12 text-center">
            <AlertCircle className="h-12 w-12 mx-auto mb-4 text-red-500" />
            <p className="text-lg font-medium">Project ID is required</p>
            <p className="text-muted-foreground">
              Please navigate from the workflows list
            </p>
            <Button className="mt-4" onClick={() => navigate({ to: '/workflows' })}>
              <ArrowLeft className="h-4 w-4 mr-2" />
              Back to Workflows
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
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

  const isReadOnly = workflow.status === 'ARCHIVED'

  return (
    <div className="h-screen flex flex-col">
      {/* Header */}
      <header className="border-b px-4 py-3 flex items-center justify-between bg-card">
        <div className="flex items-center gap-4">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate({ to: '/workflows' })}
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <div className="flex items-center gap-2">
              <Input
                value={localName}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                  setLocalName(e.target.value)
                  setHasChanges(true)
                }}
                className="h-8 text-lg font-semibold border-transparent hover:border-input focus:border-input"
                disabled={isReadOnly}
              />
              <Badge className={statusColors[workflow.status]}>
                {workflow.status}
              </Badge>
              <span className="text-sm text-muted-foreground">v{workflow.version}</span>
              {invalidCount > 0 && (
                <Badge variant="destructive" className="gap-1">
                  <AlertCircle className="h-3 w-3" />
                  {invalidCount} invalid
                </Badge>
              )}
            </div>
            <p className="text-sm text-muted-foreground">
              {workflow.projectName} • {localSteps.length} steps
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {saveError && (
            <span
              className="text-sm text-red-600 max-w-xs truncate"
              title={saveError}
            >
              {saveError}
            </span>
          )}
          {hasChanges && (
            <span className="text-sm text-yellow-600">Unsaved changes</span>
          )}
          {workflow.status === 'PUBLISHED' && (
            <Button
              size="sm"
              variant="default"
              onClick={() => setRunDialogOpen(true)}
              disabled={hasChanges}
              title={hasChanges ? 'Save changes before running' : undefined}
            >
              <Play className="h-4 w-4 mr-2" />
              Run
            </Button>
          )}
          {!isReadOnly && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={handleSave}
                disabled={updateMutation.isPending || !hasChanges}
              >
                {updateMutation.isPending ? (
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                ) : (
                  <Save className="h-4 w-4 mr-2" />
                )}
                Save
              </Button>
              {workflow.status === 'DRAFT' && (
                <Button
                  size="sm"
                  onClick={() => publishMutation.mutate()}
                  disabled={
                    publishMutation.isPending ||
                    hasChanges ||
                    !validation.ok
                  }
                  title={
                    !validation.ok
                      ? 'Fix validation errors before publishing'
                      : hasChanges
                        ? 'Save changes before publishing'
                        : undefined
                  }
                >
                  {publishMutation.isPending ? (
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  ) : (
                    <Play className="h-4 w-4 mr-2" />
                  )}
                  Publish
                </Button>
              )}
              {workflow.status !== 'ARCHIVED' && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => archiveMutation.mutate()}
                  disabled={archiveMutation.isPending}
                >
                  <Archive className="h-4 w-4 mr-2" />
                  Archive
                </Button>
              )}
            </>
          )}
        </div>
      </header>

      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left rail */}
        <div className="w-72 border-r overflow-y-auto bg-card/30">
          <LeftRailSection
            title="Steps"
            open={openSection === 'steps'}
            onToggle={() =>
              setOpenSection(openSection === 'steps' ? 'steps' : 'steps')
            }
            forceOpen={openSection === 'steps'}
            onOpenChange={() => setOpenSection('steps')}
          >
            {localSteps.length === 0 ? (
              <p className="text-xs text-muted-foreground py-2">
                No steps yet. Add steps from the canvas.
              </p>
            ) : (
              <ul className="space-y-1">
                {localSteps.map((s, idx) => {
                  const invalid = validation.invalidStepIds.has(s.id)
                  return (
                    <li
                      key={s.id}
                      className={`flex items-center gap-1 px-2 py-1 rounded text-xs cursor-pointer ${
                        selectedStepId === s.id
                          ? 'bg-accent'
                          : 'hover:bg-accent/50'
                      }`}
                      onClick={() => setSelectedStepId(s.id)}
                    >
                      <span className="flex-1 truncate">
                        {invalid && (
                          <AlertCircle className="h-3 w-3 inline mr-1 text-red-600" />
                        )}
                        {idx + 1}. {s.name || s.id}
                      </span>
                      {!isReadOnly && (
                        <>
                          <Button
                            type="button"
                            size="icon"
                            variant="ghost"
                            className="h-5 w-5"
                            disabled={idx === 0}
                            onClick={(e) => {
                              e.stopPropagation()
                              if (idx === 0) return
                              const next = [...localSteps]
                              ;[next[idx - 1], next[idx]] = [next[idx], next[idx - 1]]
                              handleStepsChange(next)
                            }}
                          >
                            <ChevronUp className="h-3 w-3" />
                          </Button>
                          <Button
                            type="button"
                            size="icon"
                            variant="ghost"
                            className="h-5 w-5"
                            disabled={idx === localSteps.length - 1}
                            onClick={(e) => {
                              e.stopPropagation()
                              if (idx === localSteps.length - 1) return
                              const next = [...localSteps]
                              ;[next[idx], next[idx + 1]] = [next[idx + 1], next[idx]]
                              handleStepsChange(next)
                            }}
                          >
                            <ChevronDown className="h-3 w-3" />
                          </Button>
                        </>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </LeftRailSection>

          <LeftRailSection
            title="Inputs"
            open={openSection === 'inputs'}
            forceOpen={openSection === 'inputs'}
            onOpenChange={() => setOpenSection('inputs')}
          >
            <InputSchemaEditor
              value={localInputSchema}
              readOnly={isReadOnly}
              onChange={(v) => {
                setLocalInputSchema(v)
                setHasChanges(true)
              }}
            />
          </LeftRailSection>

          <LeftRailSection
            title="Artifacts repository"
            open={openSection === 'artifacts'}
            forceOpen={openSection === 'artifacts'}
            onOpenChange={() => setOpenSection('artifacts')}
          >
            <ArtifactsRepoSection
              projectId={projectId}
              value={localArtifactsRepo}
              readOnly={isReadOnly}
              onChange={(v) => {
                setLocalArtifactsRepo(v)
                setHasChanges(true)
              }}
            />
          </LeftRailSection>
        </div>

        {/* Canvas */}
        <div className="flex-1 relative">
          <WorkflowCanvas
            steps={localSteps}
            onStepsChange={handleStepsChange}
            onStepSelect={setSelectedStepId}
            selectedStepId={selectedStepId}
            readOnly={isReadOnly}
            agentProfiles={profilesForCanvas}
            invalidStepIds={validation.invalidStepIds}
          />
        </div>

        {/* Properties Panel */}
        <div className="w-80 border-l overflow-hidden">
          <StepPropertiesPanel
            step={selectedStep}
            allSteps={localSteps}
            profiles={profiles || []}
            onUpdate={handleStepUpdate}
            onStepsBulkUpdate={handleStepsBulkUpdate}
            onDelete={handleStepDelete}
            onClose={() => setSelectedStepId(null)}
            errors={
              selectedStep ? validation.byStepId[selectedStep.id] : undefined
            }
          />
        </div>
      </div>

      {runDialogOpen && workflow && (
        <RunWorkflowDialog
          workflow={workflow}
          open={runDialogOpen}
          onClose={() => setRunDialogOpen(false)}
        />
      )}
    </div>
  )
}

interface LeftRailSectionProps {
  title: string
  open: boolean
  forceOpen?: boolean
  onToggle?: () => void
  onOpenChange?: () => void
  children: React.ReactNode
}

function LeftRailSection({
  title,
  open,
  onOpenChange,
  children,
}: LeftRailSectionProps) {
  return (
    <div className="border-b">
      <button
        type="button"
        className="w-full px-3 py-2 text-left text-xs font-semibold flex items-center justify-between hover:bg-accent/30"
        onClick={() => onOpenChange?.()}
      >
        <span>{title}</span>
        {open ? (
          <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronRight className="h-3 w-3" />
        )}
      </button>
      {open && <div className="px-3 pb-3">{children}</div>}
    </div>
  )
}
