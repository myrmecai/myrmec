// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, useCallback, useRef, useEffect } from 'react'
import { workflowsApi, modelsApi, type Model } from '@/lib/api'
import { useAgentProfiles } from '@/lib/use-agent-profiles'
import { InputSchemaEditor } from '@/components/workflow/InputSchemaEditor'
import { RunWorkflowDialog } from '@/components/workflow/RunWorkflowDialog'
import {
  WorkflowYamlEditor,
  type WorkflowYamlEditorApi,
} from './WorkflowYamlEditor'
import type { CompiledWorkflow } from '@/lib/workflow-yaml-convert'
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
  ChevronDown,
  ChevronRight,
} from 'lucide-react'

const statusColors: Record<string, string> = {
  DRAFT: 'bg-gray-500',
  PUBLISHED: 'bg-green-600',
  DISABLED: 'bg-yellow-600',
  ARCHIVED: 'bg-red-600',
}

/**
 * The workflow detail page: header lifecycle (name, badges, Save /
 * Publish / Archive / Run) + the YAML authoring surface. Step authoring
 * is YAML-only (spec 2026-09-08-workflow-yaml-authoring-design); the
 * canvas lives inside the YAML editor's read-only Preview tab.
 */
export function WorkflowEditor({ workflowId }: { workflowId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [localName, setLocalName] = useState('')
  const [hasChanges, setHasChanges] = useState(false)
  const [saveInFlight, setSaveInFlight] = useState(false)
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [openSection, setOpenSection] = useState<'inputs' | null>(null)
  const [compiled, setCompiled] = useState<CompiledWorkflow | null>(null)

  const yamlEditorApiRef = useRef<WorkflowYamlEditorApi | null>(null)

  const { projectId } = useSearch({ strict: false }) as { projectId?: string }

  const { data: workflow, isLoading, error } = useQuery({
    queryKey: ['workflow', projectId, workflowId],
    queryFn: () => workflowsApi.get(projectId!, workflowId),
    enabled: !!projectId,
  })

  const handleDirtyChange = useCallback((dirty: boolean) => {
    setHasChanges(dirty)
  }, [])

  const handleCompiledChange = useCallback((next: CompiledWorkflow | null) => {
    setCompiled(next)
  }, [])

  const handlePendingChange = useCallback((pending: boolean) => {
    setSaveInFlight(pending)
  }, [])

  // Initialize the name once per workflow/version (not on refetch).
  useEffect(() => {
    if (workflow) {
      setLocalName(workflow.name)
      setHasChanges(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflow?.id, workflow?.version])

  const { data: profiles } = useAgentProfiles()
  // Public active-model roster — authoring surfaces only need the code
  // set for validation; the admin list (pricing, apiEndpoint) stays with
  // the admin UI.
  const { data: models } = useQuery<Model[]>({
    queryKey: ['models', 'active'],
    queryFn: () => modelsApi.listActive(),
    staleTime: 5 * 60 * 1000,
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

  const handleNameChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setLocalName(e.target.value)
    setHasChanges(true)
  }, [])

  const handleSave = useCallback(() => {
    yamlEditorApiRef.current?.save()
  }, [])

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
  const yamlValid = compiled !== null
  // Save exists to persist pending edits — it must stay clickable while
  // dirty (that's its whole job). Gate on validity and in-flight state;
  // the YAML editor's imperative handle no-ops invalid documents.
  const saveDisabled = !yamlValid || saveInFlight

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
                onChange={handleNameChange}
                className="h-8 text-lg font-semibold border-transparent hover:border-input focus:border-input"
                disabled={isReadOnly}
              />
              <Badge className={statusColors[workflow.status]}>
                {workflow.status}
              </Badge>
              <span className="text-sm text-muted-foreground">v{workflow.version}</span>
            </div>
            <p className="text-sm text-muted-foreground">
              {workflow.projectName}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
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
                disabled={saveDisabled}
                data-testid="workflow-save-button"
                title={saveDisabled && !yamlValid ? 'Fix validation issues before saving' : undefined}
              >
                <Save className="h-4 w-4 mr-2" />
                Save
              </Button>
              {workflow.status === 'DRAFT' && (
                <Button
                  size="sm"
                  onClick={() => publishMutation.mutate()}
                  disabled={publishMutation.isPending || hasChanges || !yamlValid}
                  data-testid="workflow-publish-button"
                  title={
                    !yamlValid
                      ? 'Fix validation issues before publishing'
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

      {/* Main content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left rail: workflow inputs only. Steps and the artifacts
            repository are authored in the YAML (the source: block owns
            artifactsRepo per the spec). */}
        <div className="w-72 border-r overflow-y-auto bg-card/30">
          <LeftRailSection
            title="Inputs"
            open={openSection === 'inputs'}
            onOpenChange={() =>
              setOpenSection(openSection === 'inputs' ? null : 'inputs')
            }
          >
            <p className="text-xs text-muted-foreground pb-2">
              Input schema for run dialogs. Steps live in the YAML.
            </p>
            <InputSchemaEditor
              value={workflow.inputSchema}
              readOnly={isReadOnly}
              onChange={() => setHasChanges(true)}
            />
          </LeftRailSection>
        </div>

        {/* YAML authoring surface */}
        <div className="flex-1 flex overflow-hidden">
          {profiles && models ? (
            <WorkflowYamlEditor
              workflow={workflow}
              projectId={projectId}
              profiles={profiles}
              models={models}
              readOnly={isReadOnly}
              onDirtyChange={handleDirtyChange}
              onCompiledChange={handleCompiledChange}
              onPendingChange={handlePendingChange}
              apiRef={yamlEditorApiRef}
            />
          ) : (
            <div className="flex-1 flex items-center justify-center">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          )}
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