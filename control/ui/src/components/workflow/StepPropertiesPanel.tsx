import { useEffect, useState } from 'react'
import Editor from '@monaco-editor/react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Trash2, X } from 'lucide-react'
import type { WorkflowStep, AgentProfile } from '@/lib/api'
import type { StepFieldErrors } from '@/lib/workflow-schema'
import { cn } from '@/lib/utils'

interface StepPropertiesPanelProps {
  step: WorkflowStep | null
  allSteps: WorkflowStep[]
  profiles: AgentProfile[]
  onUpdate: (step: WorkflowStep) => void
  onStepsBulkUpdate?: (steps: WorkflowStep[]) => void
  onDelete: (stepId: string) => void
  onClose: () => void
  errors?: StepFieldErrors
}

export function StepPropertiesPanel({
  step,
  allSteps,
  profiles,
  onUpdate,
  onStepsBulkUpdate,
  onDelete,
  onClose,
  errors,
}: StepPropertiesPanelProps) {
  const [localStep, setLocalStep] = useState<WorkflowStep | null>(step)

  useEffect(() => {
    setLocalStep(step)
  }, [step])

  if (!localStep) {
    return (
      <Card className="h-full">
        <CardHeader>
          <CardTitle className="text-sm">Step Properties</CardTitle>
        </CardHeader>
        <CardContent className="text-muted-foreground text-sm">
          Select a step to edit its properties
        </CardContent>
      </Card>
    )
  }

  const handleChange = <K extends keyof WorkflowStep>(
    field: K,
    value: WorkflowStep[K]
  ) => {
    const updated = { ...localStep, [field]: value }
    setLocalStep(updated)
    onUpdate(updated)
  }

  const handleTransitionChange = (
    type: 'SUCCESS' | 'FAILURE' | 'TIMEOUT',
    targetId: string | undefined
  ) => {
    if (type === 'SUCCESS' && onStepsBulkUpdate) {
      // SUCCESS edges are also reflected in target.dependsOn (canonical for canvas).
      // Update transitions on this step and dependsOn on the old/new target steps in one pass.
      const previousTarget = localStep.transitions?.SUCCESS
      const newTransitions = { ...(localStep.transitions || {}) }
      if (targetId) newTransitions.SUCCESS = targetId
      else delete newTransitions.SUCCESS

      const next = allSteps.map((s) => {
        if (s.id === localStep.id) {
          return { ...s, transitions: newTransitions }
        }
        // Remove this step from a previous target's dependsOn
        if (previousTarget && s.id === previousTarget && s.id !== targetId) {
          return {
            ...s,
            dependsOn: (s.dependsOn || []).filter((d) => d !== localStep.id),
          }
        }
        // Add this step to the new target's dependsOn (if not already present)
        if (targetId && s.id === targetId) {
          const deps = s.dependsOn || []
          if (deps.includes(localStep.id)) return s
          return { ...s, dependsOn: [...deps, localStep.id] }
        }
        return s
      })

      setLocalStep({ ...localStep, transitions: newTransitions })
      onStepsBulkUpdate(next)
      return
    }

    const newTransitions = { ...(localStep.transitions || {}) }
    if (targetId) {
      newTransitions[type] = targetId
    } else {
      delete newTransitions[type]
    }
    handleChange('transitions', newTransitions)
  }

  // Other steps that can be targets (excluding self)
  const otherSteps = allSteps.filter((s) => s.id !== localStep.id)

  // SUCCESS target: prefer explicit transitions.SUCCESS, else fall back to the
  // step that has this step in its dependsOn (canonical canvas representation).
  const successTarget =
    localStep.transitions?.SUCCESS ||
    allSteps.find((s) => s.dependsOn?.includes(localStep.id))?.id ||
    ''

  return (
    <Card className="h-full flex flex-col">
      <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm">Step Properties</CardTitle>
        <Button variant="ghost" size="icon" onClick={onClose}>
          <X className="h-4 w-4" />
        </Button>
      </CardHeader>
      <CardContent className="flex-1 overflow-y-auto space-y-4">
        {/* Basic Info */}
        <div className="space-y-3">
          <div>
            <Label className="text-xs">Step ID</Label>
            <Input
              value={localStep.id}
              onChange={(e) => handleChange('id', e.target.value)}
              className={cn(
                'h-8 text-sm',
                errors?.id && 'border-red-500 focus-visible:ring-red-500'
              )}
              aria-invalid={!!errors?.id}
            />
            {errors?.id && (
              <p className="text-xs text-red-600 mt-1">{errors.id}</p>
            )}
          </div>
          <div>
            <Label className="text-xs">Name</Label>
            <Input
              value={localStep.name}
              onChange={(e) => handleChange('name', e.target.value)}
              className={cn(
                'h-8 text-sm',
                errors?.name && 'border-red-500 focus-visible:ring-red-500'
              )}
              aria-invalid={!!errors?.name}
            />
            {errors?.name && (
              <p className="text-xs text-red-600 mt-1">{errors.name}</p>
            )}
          </div>
          <div>
            <Label className="text-xs">Agent Profile</Label>
            <Select
              value={localStep.agentProfileId}
              onValueChange={(v) => handleChange('agentProfileId', v)}
            >
              <SelectTrigger
                className={cn(
                  'h-8 text-sm',
                  errors?.agentProfileId &&
                    'border-red-500 focus:ring-red-500'
                )}
                aria-invalid={!!errors?.agentProfileId}
              >
                <SelectValue placeholder="Select profile..." />
              </SelectTrigger>
              <SelectContent>
                {profiles.map((profile) => (
                  <SelectItem key={profile.id} value={profile.id}>
                    {profile.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors?.agentProfileId && (
              <p className="text-xs text-red-600 mt-1">{errors.agentProfileId}</p>
            )}
          </div>
        </div>

        {/* Prompt Editor */}
        <div>
          <Label className="text-xs">Prompt</Label>
          <p className="text-xs text-muted-foreground mb-1">
            Use {'{{variable}}'} for template variables
          </p>
          <div
            className={cn(
              'border rounded-md overflow-hidden h-[200px]',
              errors?.prompt && 'border-red-500'
            )}
          >
            <Editor
              height="100%"
              language="markdown"
              value={localStep.prompt || ''}
              onChange={(value) => handleChange('prompt', value || '')}
              options={{
                minimap: { enabled: false },
                lineNumbers: 'off',
                fontSize: 12,
                wordWrap: 'on',
                scrollBeyondLastLine: false,
              }}
              theme="vs-light"
            />
          </div>
          {errors?.prompt && (
            <p className="text-xs text-red-600 mt-1">{errors.prompt}</p>
          )}
        </div>

        {/* Execution Settings */}
        <div className="grid grid-cols-2 gap-2">
          <div>
            <Label className="text-xs">Timeout (seconds)</Label>
            <Input
              type="number"
              value={localStep.timeoutSeconds || 300}
              onChange={(e) =>
                handleChange('timeoutSeconds', parseInt(e.target.value) || 300)
              }
              className={cn(
                'h-8 text-sm',
                errors?.timeoutSeconds &&
                  'border-red-500 focus-visible:ring-red-500'
              )}
              aria-invalid={!!errors?.timeoutSeconds}
            />
            {errors?.timeoutSeconds && (
              <p className="text-xs text-red-600 mt-1">
                {errors.timeoutSeconds}
              </p>
            )}
          </div>
          <div>
            <Label className="text-xs">Max Retries</Label>
            <Input
              type="number"
              value={localStep.maxRetries || 0}
              onChange={(e) =>
                handleChange('maxRetries', parseInt(e.target.value) || 0)
              }
              className={cn(
                'h-8 text-sm',
                errors?.maxRetries &&
                  'border-red-500 focus-visible:ring-red-500'
              )}
              aria-invalid={!!errors?.maxRetries}
            />
            {errors?.maxRetries && (
              <p className="text-xs text-red-600 mt-1">{errors.maxRetries}</p>
            )}
          </div>
        </div>

        {/* Transitions */}
        <div>
          <Label className="text-xs mb-2 block">Transitions</Label>
          {errors?.transitions && (
            <p className="text-xs text-red-600 mb-2">{errors.transitions}</p>
          )}
          {errors?.dependsOn && (
            <p className="text-xs text-red-600 mb-2">{errors.dependsOn}</p>
          )}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <span className="w-16 text-xs text-green-600 font-medium">
                Success:
              </span>
              <Select
                value={successTarget || '__none__'}
                onValueChange={(v) =>
                  handleTransitionChange('SUCCESS', v === '__none__' ? undefined : v)
                }
              >
                <SelectTrigger className="h-8 text-sm flex-1">
                  <SelectValue placeholder="End workflow" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">End workflow</SelectItem>
                  {otherSteps.map((s) => (
                    <SelectItem key={s.id} value={s.id}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-16 text-xs text-red-600 font-medium">
                Failure:
              </span>
              <Select
                value={localStep.transitions?.FAILURE || '__none__'}
                onValueChange={(v) =>
                  handleTransitionChange('FAILURE', v === '__none__' ? undefined : v)
                }
              >
                <SelectTrigger className="h-8 text-sm flex-1">
                  <SelectValue placeholder="End workflow" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">End workflow</SelectItem>
                  {otherSteps.map((s) => (
                    <SelectItem key={s.id} value={s.id}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-16 text-xs text-yellow-600 font-medium">
                Timeout:
              </span>
              <Select
                value={localStep.transitions?.TIMEOUT || '__none__'}
                onValueChange={(v) =>
                  handleTransitionChange('TIMEOUT', v === '__none__' ? undefined : v)
                }
              >
                <SelectTrigger className="h-8 text-sm flex-1">
                  <SelectValue placeholder="End workflow" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">End workflow</SelectItem>
                  {otherSteps.map((s) => (
                    <SelectItem key={s.id} value={s.id}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
        </div>

        {/* Delete Button */}
        <Button
          variant="destructive"
          size="sm"
          className="w-full"
          onClick={() => onDelete(localStep.id)}
        >
          <Trash2 className="h-4 w-4 mr-2" />
          Delete Step
        </Button>
      </CardContent>
    </Card>
  )
}
