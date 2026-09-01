// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import {
  type ConnectionType,
  type CreateConnectionConfigRequest,
} from '@/lib/api'
import { Scope } from '@/lib/domain-constants'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { SecretSelect } from '@/features/platform/security/secrets/SecretSelect'
import { TYPE_LABELS, TYPE_CONFIG_FIELDS, type ConfigField } from '../shared/constants'
import { RequiredMark } from '@/components/ui/required-marks'

export interface ConnectionConfigTypeFieldsProps {
  type: ConnectionType
  defaultScope: Scope
  defaultProjectId?: string | null
  onSubmit: (data: CreateConnectionConfigRequest & { url?: string; config?: Record<string, unknown> }) => void
  isLoading: boolean
  error?: string
}

export function ConnectionConfigTypeFields({
  type,
  defaultScope,
  defaultProjectId,
  onSubmit,
  isLoading,
  error,
}: ConnectionConfigTypeFieldsProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [scope, setScope] = useState<Scope>(defaultScope)
  const [projectId, setProjectId] = useState(defaultProjectId ?? '')
  const [url, setUrl] = useState('')
  const [configValues, setConfigValues] = useState<Record<string, string>>({})
  const [credentialSecretId, setCredentialSecretId] = useState<string | null>(null)

  const configFields = TYPE_CONFIG_FIELDS[type] || []

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    e.stopPropagation()
    const payload: CreateConnectionConfigRequest & { url?: string; config?: Record<string, unknown> } = {
      name: name.trim(),
      description: description || undefined,
      type,
      scope,
      credentialSecretId,
      url: url.trim() || undefined,
    }
    if (scope === Scope.PROJECT) {
      payload.projectId = projectId || null
    }
    const typedConfig: Record<string, unknown> = {}
    for (const field of configFields) {
      const raw = configValues[field.key]?.trim() ?? ''
      if (!raw) continue
      typedConfig[field.key] = field.type === 'number' ? Number(raw) : raw
    }
    if (Object.keys(typedConfig).length > 0) {
      payload.config = typedConfig
    }
    onSubmit(payload)
  }

  const typeLabel = TYPE_LABELS[type] || type

  return (
    <form onSubmit={handleSubmit}>
      <DialogHeader>
        <DialogTitle>Create {typeLabel} Connection</DialogTitle>
        <DialogDescription>
          Choose scope and credential secret for the new {typeLabel.toLowerCase()} config.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        {error && (
          <div className="p-3 text-sm text-destructive bg-destructive/10 rounded-md">{error}</div>
        )}
        <div className="space-y-2">
          <Label htmlFor="cc-name">Name<RequiredMark /></Label>
          <Input
            id="cc-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., OpenAI production"
            required
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="cc-description">Description</Label>
          <Textarea
            id="cc-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Optional description"
            rows={3}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="cc-scope">Scope<RequiredMark /></Label>
          <Select
            value={scope}
            onValueChange={(v) => setScope(v as Scope)}
          >
            <SelectTrigger id="cc-scope">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={Scope.ORGANIZATION}>System-wide</SelectItem>
              <SelectItem value={Scope.PROJECT}>Project</SelectItem>
            </SelectContent>
          </Select>
        </div>
        {scope === Scope.PROJECT && (
          <div className="space-y-2">
            <Label htmlFor="cc-project-id">Project<RequiredMark /></Label>
            <Input
              id="cc-project-id"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              placeholder="Project UUID"
              required
            />
          </div>
        )}
        <div className="space-y-2">
          <Label htmlFor="cc-url">URL<RequiredMark /></Label>
          <Input
            id="cc-url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder={type === 'GIT' ? 'https://github.com/org/repo.git' : 'https://api.example.com'}
            required
          />
        </div>
        {configFields.map((field) => (
          <ConfigFieldInput
            key={field.key}
            field={field}
            value={configValues[field.key] ?? ''}
            onChange={(v) =>
              setConfigValues((prev) => ({ ...prev, [field.key]: v }))
            }
            required={type === 'HTTP' && field.key === 'testEndpoint'}
          />
        ))}
        <div className="space-y-2">
          <Label htmlFor="cc-credential-secret">Credential Secret</Label>
          <SecretSelect
            value={credentialSecretId}
            onChange={setCredentialSecretId}
            placeholder="Select a credential secret"
            filterByConnectionType={type}
          />
          <p className="text-xs text-muted-foreground">
            You can create a new secret inline if none exists.
          </p>
        </div>
      </div>

      <DialogFooter>
        <Button
          type="submit"
          disabled={isLoading || !name.trim() || (type === 'HTTP' && !url.trim())}
        >
          {isLoading ? 'Creating...' : 'Create & Select'}
        </Button>
      </DialogFooter>
    </form>
  )
}

function ConfigFieldInput({
  field,
  value,
  onChange,
  required,
}: {
  field: ConfigField
  value: string
  onChange: (value: string) => void
  required?: boolean
}) {
  const id = `cc-config-${field.key}`
  if (field.type === 'select') {
    return (
      <div className="space-y-2">
        <Label htmlFor={id}>{field.label}{required && <RequiredMark />}</Label>
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger id={id}>
            <SelectValue placeholder={field.placeholder ?? 'Select...'} />
          </SelectTrigger>
          <SelectContent>
            {field.options?.map((opt) => (
              <SelectItem key={opt} value={opt}>
                {opt}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    )
  }
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{field.label}{required && <RequiredMark />}</Label>
      <Input
        id={id}
        type={field.type === 'number' ? 'number' : 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={field.placeholder}
      />
    </div>
  )
}
