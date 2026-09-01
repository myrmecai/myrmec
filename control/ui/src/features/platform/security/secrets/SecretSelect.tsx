// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { globalSecretsApi, type CreateSecretRequest } from '@/lib/api'
import { ALLOWED_SECRET_TYPES } from '@/features/platform/connections/shared/constants'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Plus } from 'lucide-react'
import { CreateSecretForm } from './CreateSecretForm'

export interface SecretSelectProps {
  value: string | null
  onChange: (secretId: string | null) => void
  scope?: 'global'
  placeholder?: string
  /**
   * When set, only secrets whose type is in the allowed list for this
   * connection type are shown. Uses ALLOWED_SECRET_TYPES from
   * connections/shared/constants.
   */
  filterByConnectionType?: import('@/lib/api').ConnectionType
}

const NONE_VALUE = '__none__'
const CREATE_VALUE = '__create__'

export function SecretSelect({
  value,
  onChange,
  scope = 'global',
  placeholder = 'Select a secret',
  filterByConnectionType,
}: SecretSelectProps) {
  const [creating, setCreating] = useState(false)
  const queryClient = useQueryClient()

  const { data: secrets = [] } = useQuery({
    queryKey: ['secrets', scope],
    queryFn: () => globalSecretsApi.list(),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateSecretRequest) => globalSecretsApi.create(data),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['secrets', scope] })
      // Auto-select the newly created secret but keep the dialog open
      // so the user can verify their selection before closing.
      onChange(created.id)
      setCreating(false)
    },
  })

  const selected = secrets.find((s) => s.id === value)
  const selectValue = value ?? NONE_VALUE

  // Filter secrets by allowed credential types for the connection type.
  const allowedTypes = filterByConnectionType
    ? ALLOWED_SECRET_TYPES[filterByConnectionType] ?? []
    : null
  const visibleSecrets = allowedTypes
    ? secrets.filter((s) => allowedTypes.includes(s.type))
    : secrets

  const handleValueChange = (next: string) => {
    if (next === CREATE_VALUE) {
      setCreating(true)
      return
    }
    onChange(next === NONE_VALUE ? null : next)
  }

  return (
    <>
      <Select value={selectValue} onValueChange={handleValueChange}>
        <SelectTrigger id="cc-credential-secret-trigger" aria-label={placeholder}>
          <SelectValue placeholder={placeholder}>
            {selected ? selected.name : placeholder}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={NONE_VALUE}>None</SelectItem>
          </SelectGroup>
          <SelectSeparator />
          <SelectGroup>
            {visibleSecrets.map((secret) => (
              <SelectItem key={secret.id} value={secret.id}>
                {secret.name}
              </SelectItem>
            ))}
          </SelectGroup>
          <SelectSeparator />
          <SelectItem value={CREATE_VALUE}>
            <span className="flex items-center gap-2 font-medium">
              <Plus className="h-4 w-4" /> Create new secret
            </span>
          </SelectItem>
        </SelectContent>
      </Select>

      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent
          className="max-w-xl"
          onInteractOutside={(e) => e.preventDefault()}
        >
          <CreateSecretForm
            onSubmit={(data) => createMutation.mutate(data)}
            isLoading={createMutation.isPending}
            error={createMutation.error?.message}
          />
        </DialogContent>
      </Dialog>
    </>
  )
}
