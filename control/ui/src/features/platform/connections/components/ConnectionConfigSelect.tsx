// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { connectionConfigApi, type ConnectionConfig } from '@/lib/api'
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
import { CreateConnectionConfigWizard } from './CreateConnectionConfigWizard'
import { useAuth } from '@/lib/auth'
import { Scope } from '@/lib/domain-constants'

export interface ConnectionConfigSelectProps {
  value: string | null
  onChange: (configId: string | null) => void
  filter?: (config: ConnectionConfig) => boolean
  placeholder?: string
}

const NONE_VALUE = '__none__'
const CREATE_VALUE = '__create__'

export function ConnectionConfigSelect({
  value,
  onChange,
  filter,
  placeholder = 'Select a connection config',
}: ConnectionConfigSelectProps) {
  const [creating, setCreating] = useState(false)
  // Keep configs created inline available as Select options until the query
  // cache refetches. Otherwise Radix Select can clear the controlled value
  // back to empty when the freshly created option is briefly absent.
  const [createdOptions, setCreatedOptions] = useState<ConnectionConfig[]>([])
  const { isPlatformAdmin, hasSystemRole } = useAuth()

  const { data: configs = [] } = useQuery({
    queryKey: ['connection-configs'],
    queryFn: () => connectionConfigApi.list(),
  })

  // Permission-based default scope. Admin users see system-wide as the
  // default; project-scoped users default to PROJECT if we ever have a
  // projectId available. For now we keep this simple: ORGANIZATION if allowed,
  // PROJECT otherwise.
  const defaultScope: Scope = isPlatformAdmin || hasSystemRole('ORG_ADMIN')
    ? Scope.ORGANIZATION
    : hasSystemRole('EDITOR') || hasSystemRole('VIEWER')
      ? Scope.ORGANIZATION
      : Scope.PROJECT

  const allConfigs = useMemo(() => {
    const merged = [...configs]
    for (const c of createdOptions) {
      if (!merged.some((m) => m.id === c.id)) {
        merged.push(c)
      }
    }
    return merged
  }, [configs, createdOptions])

  const filtered = filter ? allConfigs.filter(filter) : allConfigs
  const selected = allConfigs.find((c) => c.id === value)
  const selectValue = value ?? NONE_VALUE

  const handleValueChange = (next: string) => {
    if (next === CREATE_VALUE) {
      setCreating(true)
      return
    }
    onChange(next === NONE_VALUE ? null : next)
  }

  const handleCreated = (config: ConnectionConfig) => {
    setCreatedOptions((prev) => (prev.some((c) => c.id === config.id) ? prev : [...prev, config]))
    // Directly set the parent's controlled value so the form is submitted
    // with the newly created config even if Radix Select's controlled flush
    // clears the Select's internal value.
    setTimeout(() => onChange(config.id), 0)
  }

  return (
    <>
      <Select value={selectValue} onValueChange={handleValueChange}>
        <SelectTrigger aria-label={placeholder}>
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
            {filtered.map((config) => (
              <SelectItem key={config.id} value={config.id}>
                {config.name}
              </SelectItem>
            ))}
          </SelectGroup>
          <SelectSeparator />
          <SelectItem value={CREATE_VALUE}>
            <span className="flex items-center gap-2 font-medium">
              <Plus className="h-4 w-4" /> Create new connection config
            </span>          </SelectItem>
        </SelectContent>
      </Select>

      <CreateConnectionConfigWizard
        open={creating}
        onOpenChange={setCreating}
        defaultScope={defaultScope}
        onCreated={handleCreated}
      />
    </>
  )
}
