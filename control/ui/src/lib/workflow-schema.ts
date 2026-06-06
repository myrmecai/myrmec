import { z } from 'zod'
import type { WorkflowStep } from '@/lib/api'

const STEP_ID_PATTERN = /^[a-zA-Z0-9_-]+$/

/**
 * Metadata-only form schema for the workflow list create/edit dialog.
 * Steps/inputSchema/artifactsRepo are authored in the editor route, not here.
 */
export const workflowMetadataSchema = z.object({
  projectId: z.string().uuid({ message: 'Project is required.' }),
  name: z
    .string()
    .trim()
    .min(1, 'Name is required.')
    .max(120, 'Name must be 120 characters or fewer.'),
  description: z
    .string()
    .trim()
    .max(2000, 'Description must be 2000 characters or fewer.')
    .optional()
    .or(z.literal('').transform(() => undefined)),
})

export type WorkflowMetadataFormValues = z.infer<typeof workflowMetadataSchema>

/**
 * Per-step schema. Cross-step validation (transition targets exist, no
 * duplicate ids) is layered on top via `validateWorkflowSteps` below.
 */
export const stepSchema = z.object({
  id: z
    .string()
    .trim()
    .min(1, 'Step ID is required.')
    .max(64, 'Step ID must be 64 characters or fewer.')
    .regex(
      STEP_ID_PATTERN,
      'Use letters, digits, hyphens or underscores only.'
    ),
  name: z
    .string()
    .trim()
    .min(1, 'Name is required.')
    .max(120, 'Name must be 120 characters or fewer.'),
  agentProfileId: z
    .string()
    .uuid({ message: 'Select an agent profile.' }),
  prompt: z.string().max(50_000).optional().or(z.literal('')),
  dependsOn: z.array(z.string()).optional(),
  transitions: z.record(z.string()).optional(),
  timeoutSeconds: z
    .number({ invalid_type_error: 'Must be a number.' })
    .int('Must be an integer.')
    .min(1, 'Must be at least 1 second.')
    .max(86_400, 'Must be at most 24 hours.')
    .optional(),
  maxRetries: z
    .number({ invalid_type_error: 'Must be a number.' })
    .int('Must be an integer.')
    .min(0, 'Cannot be negative.')
    .max(10, 'Must be at most 10.')
    .optional(),
})

export type StepFieldErrors = Partial<Record<keyof WorkflowStep, string>>

export interface StepValidationResult {
  /** Per-step, per-field error messages. Steps without entries are valid. */
  byStepId: Record<string, StepFieldErrors>
  /** All invalid step IDs (handy for canvas highlighting). */
  invalidStepIds: Set<string>
  /** True when every step is valid AND cross-step rules pass. */
  ok: boolean
  /** Top-level workflow-wide messages (e.g. duplicate IDs, no steps). */
  workflowErrors: string[]
}

/**
 * Validate a list of steps. Combines per-step zod validation with cross-step
 * rules (duplicate IDs, transition/dependsOn targets must exist).
 */
export function validateWorkflowSteps(
  steps: WorkflowStep[]
): StepValidationResult {
  const byStepId: Record<string, StepFieldErrors> = {}
  const invalidStepIds = new Set<string>()
  const workflowErrors: string[] = []

  if (steps.length === 0) {
    workflowErrors.push('Add at least one step before publishing.')
  }

  // Track duplicate IDs
  const idCounts = new Map<string, number>()
  steps.forEach((s) => {
    idCounts.set(s.id, (idCounts.get(s.id) ?? 0) + 1)
  })

  const validIds = new Set(steps.map((s) => s.id))

  for (const step of steps) {
    const errors: StepFieldErrors = {}
    const parsed = stepSchema.safeParse(step)
    if (!parsed.success) {
      for (const issue of parsed.error.issues) {
        const field = issue.path[0] as keyof WorkflowStep | undefined
        if (field && !errors[field]) {
          errors[field] = issue.message
        }
      }
    }

    if ((idCounts.get(step.id) ?? 0) > 1 && !errors.id) {
      errors.id = 'Step IDs must be unique.'
    }

    // Cross-step: dependsOn targets must exist (and not be self).
    if (step.dependsOn && step.dependsOn.length > 0) {
      const bad = step.dependsOn.filter(
        (id) => id === step.id || !validIds.has(id)
      )
      if (bad.length > 0 && !errors.dependsOn) {
        errors.dependsOn = `Unknown step reference: ${bad.join(', ')}`
      }
    }

    // Cross-step: transition targets must exist.
    if (step.transitions) {
      const badTargets: string[] = []
      for (const [key, target] of Object.entries(step.transitions)) {
        if (!target) continue
        if (!validIds.has(target)) badTargets.push(`${key} → ${target}`)
      }
      if (badTargets.length > 0 && !errors.transitions) {
        errors.transitions = `Unknown transition target: ${badTargets.join(
          ', '
        )}`
      }
    }

    if (Object.keys(errors).length > 0) {
      byStepId[step.id] = errors
      invalidStepIds.add(step.id)
    }
  }

  return {
    byStepId,
    invalidStepIds,
    ok: invalidStepIds.size === 0 && workflowErrors.length === 0,
    workflowErrors,
  }
}
