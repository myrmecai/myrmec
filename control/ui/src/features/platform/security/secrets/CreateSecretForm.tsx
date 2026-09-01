// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import {
  type CredentialType,
  type CreateSecretRequest,
  type SecretPayload,
} from '@/lib/api'
import {
  CREDENTIAL_TYPE_OPTIONS,
  emptyPayloadFor,
  SecretCredentialFields,
} from '@/lib/secrets-form'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RequiredMark } from '@/components/ui/required-marks'
import {
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

export interface CreateSecretFormProps {
  onSubmit: (data: CreateSecretRequest) => void
  isLoading: boolean
  error?: string
}

export function CreateSecretForm({ onSubmit, isLoading, error }: CreateSecretFormProps) {
  const [name, setName] = useState('')
  const [type, setType] = useState<CredentialType>('BEARER_TOKEN')
  const [payload, setPayload] = useState<SecretPayload>(emptyPayloadFor('BEARER_TOKEN'))

  const handleTypeChange = (next: CredentialType) => {
    setType(next)
    setPayload(emptyPayloadFor(next))
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    e.stopPropagation()
    onSubmit({ name: name.trim(), type, payload })
  }

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>New Global Secret</DialogTitle>
        <DialogDescription>
          Global secrets are visible to every project. Use sparingly.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="secret-name">Name<RequiredMark /></Label>
          <Input
            id="secret-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., shared-github-pat"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="secret-type">Type<RequiredMark /></Label>
          <Select value={type} onValueChange={(v) => handleTypeChange(v as CredentialType)}>
            <SelectTrigger id="secret-type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {CREDENTIAL_TYPE_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            {CREDENTIAL_TYPE_OPTIONS.find((o) => o.value === type)?.hint}
          </p>
        </div>
        <SecretCredentialFields payload={payload} onChange={setPayload} />
      </div>

      <DialogFooter>
        <Button type="submit" disabled={isLoading}>
          {isLoading ? 'Saving...' : 'Create Secret'}
        </Button>
      </DialogFooter>
    </form>
  )
}
