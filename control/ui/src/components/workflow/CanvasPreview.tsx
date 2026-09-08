// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { WorkflowCanvas } from '@/components/workflow/WorkflowCanvas'
import type { WorkflowStep } from '@/lib/api'

/**
 * Read-only visualization of the parsed YAML (spec §5): the existing
 * canvas fed from the compiled steps, all editing inert. The future
 * "view part" slice builds on this seam.
 */
export function CanvasPreview({
  steps,
  profiles,
}: {
  steps: WorkflowStep[]
  profiles?: Array<{ id: string; name: string }>
}) {
  return (
    <div className="h-full w-full">
      <WorkflowCanvas
        steps={steps}
        onStepsChange={() => {}}
        onStepSelect={() => {}}
        selectedStepId={null}
        readOnly
        agentProfiles={profiles}
      />
    </div>
  )
}