// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  connectionConfigApi,
  type ConnectionConfig,
  type ConnectionType,
  type CreateConnectionConfigRequest,
} from '@/lib/api'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import { Button } from '@/components/ui/button'
import { TYPE_LABELS } from '../shared/constants'
import { ConnectionConfigTypeFields } from './ConnectionConfigTypeFields'
import { Scope } from '@/lib/domain-constants'

export interface CreateConnectionConfigWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  defaultScope: Scope
  defaultProjectId?: string | null
  onCreated: (config: ConnectionConfig) => void
}

type Step = 'type' | 'details'

export function CreateConnectionConfigWizard({
  open,
  onOpenChange,
  defaultScope,
  defaultProjectId,
  onCreated,
}: CreateConnectionConfigWizardProps) {
  const [step, setStep] = useState<Step>('type')
  const [type, setType] = useState<ConnectionType>('HTTP')
  const [createError, setCreateError] = useState<string | undefined>()
  const queryClient = useQueryClient()

  const createMutation = useMutation({
    mutationFn: async (data: CreateConnectionConfigRequest & { url?: string; config?: Record<string, unknown> }) => {
      const { url, config, ...createData } = data
      const newConfig = await connectionConfigApi.create(createData)
      await connectionConfigApi.createDraft(newConfig.id)
      const draftPayload: { url?: string; config?: Record<string, unknown> } = {}
      if (url) draftPayload.url = url
      if (config && Object.keys(config).length > 0) draftPayload.config = config
      await connectionConfigApi.updateDraft(newConfig.id, draftPayload)
      await connectionConfigApi.publish(newConfig.id)
      // Return the published config so downstream pickers see the ACTIVE status.
      return connectionConfigApi.get(newConfig.id)
    },
    onSuccess: async (config) => {
      setCreateError(undefined)
      setStep('type')
      queryClient.invalidateQueries({ queryKey: ['connection-configs'] })
      onCreated(config)
      onOpenChange(false)
    },
    onError: (error: any) => {
      const details = error?.error?.details
      if (Array.isArray(details) && details.length > 0 && details[0]?.message) {
        setCreateError(details[0].message)
      } else {
        setCreateError(error?.error?.message ?? 'Failed to create connection config')
      }
    },
  })

  const handleTypeSelect = (next: ConnectionType) => {
    setType(next)
    setStep('details')
    setCreateError(undefined)
  }

  const handleClose = (nextOpen: boolean) => {
    if (!nextOpen) {
      setStep('type')
      setCreateError(undefined)
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="max-w-xl max-h-[90vh] overflow-y-auto"
        onInteractOutside={(e) => {
          // Prevent the parent dialog from closing when a nested dialog
          // (e.g. SecretSelect's create-secret dialog) unmounts and
          // triggers a focus-return event that Radix interprets as an
          // outside click on this dialog's overlay.
          e.preventDefault()
        }}
      >
        {step === 'type' ? (
          <TypeStep value={type} onChange={handleTypeSelect} />
        ) : (
          <ConnectionConfigTypeFields
            type={type}
            defaultScope={defaultScope}
            defaultProjectId={defaultProjectId}
            onSubmit={(data) => createMutation.mutate(data)}
            isLoading={createMutation.isPending}
            error={createError}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

// The Back button was removed from step 2 — users can close the dialog
// and start over if they need to change the connection type.

function TypeStep({
  value,
  onChange,
}: {
  value: ConnectionType
  onChange: (type: ConnectionType) => void
}) {
  const [pendingType, setPendingType] = useState<ConnectionType>(value)

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        e.stopPropagation()
        onChange(pendingType)
      }}
      className="space-y-4"
    >
      <div className="space-y-1">
        <h2 className="text-lg font-semibold">Create Connection Config</h2>
        <p className="text-sm text-muted-foreground">
          Select the connection type to create.
        </p>
      </div>
      <div className="space-y-2">
        <Label htmlFor="cc-type">Connection Type<RequiredMark /></Label>
        <Select
          value={pendingType}
          onValueChange={(v) => setPendingType(v as ConnectionType)}
        >
          <SelectTrigger id="cc-type">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {Object.entries(TYPE_LABELS).map(([k, label]) => (
              <SelectItem key={k} value={k}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex justify-end">
        <Button type="submit">Continue</Button>
      </div>
    </form>
  )
}
