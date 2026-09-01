// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useMemo } from 'react'
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
import type {
  QuotaScope,
  QuotaResourceType,
  QuotaPeriod,
  QuotaType,
  EnforcementMode,
  QuotaServiceType,
} from '@/lib/api'
import { formatAmount } from '../lib/format'

export interface BudgetFormValues {
  scopeType: QuotaScope
  scopeId: string
  resourceType: QuotaResourceType
  period: QuotaPeriod
  limitAmount: string
  quotaType: QuotaType
  enforcementMode: EnforcementMode
  maxExecutionAmount: string
  serviceType: QuotaServiceType | ''
  tagsJson: string
}

interface BudgetFormProps {
  values: BudgetFormValues
  onChange: (values: BudgetFormValues) => void
  onSubmit: () => void
  onCancel: () => void
  isLoading?: boolean
  submitLabel?: string
  mode: 'create' | 'edit'
  /** Name of the parent scope, e.g. "Group" or "Project". */
  parentScopeLabel?: string
  /** Parent limit amount in the same resource unit. */
  parentLimit?: number | null
  /** If editing, the original resource+period are locked. */
  disabledFields?: Array<keyof BudgetFormValues>
  /** Whether the governance profile allows non-org budget overrides. */
  budgetOverrideAllowed?: boolean
  /** Governance profile name, for showing the lock reason. */
  governanceProfileName?: string | null
  error?: string | null
}

const SCOPE_OPTIONS: { value: QuotaScope; label: string }[] = [
  { value: 'ORG', label: 'Org' },
  { value: 'GROUP', label: 'Group' },
  { value: 'PROJECT', label: 'Project' },
  { value: 'SERVICE', label: 'Service' },
]

const RESOURCE_OPTIONS: { value: QuotaResourceType; label: string }[] = [
  { value: 'COST_USD_CENTS', label: 'Cost USD' },
  { value: 'TOKENS', label: 'Tokens' },
]

const PERIOD_OPTIONS: { value: QuotaPeriod; label: string }[] = [
  { value: 'DAILY', label: 'Today' },
  { value: 'MONTHLY_CALENDAR', label: 'This month' },
  { value: 'LIFETIME', label: 'Lifetime' },
]

export function BudgetForm({
  values,
  onChange,
  onSubmit,
  onCancel,
  isLoading,
  submitLabel,
  mode,
  parentScopeLabel,
  parentLimit,
  disabledFields = [],
  budgetOverrideAllowed = true,
  governanceProfileName = null,
  error,
}: BudgetFormProps) {
  const isSubmitting = isLoading ?? false
  const primaryLabel = submitLabel ?? 'Save'
  const showType = values.scopeType !== 'ORG'
  const showMaxPerRun = values.scopeType === 'PROJECT' || values.scopeType === 'SERVICE'
  const perRunLabel =
    values.scopeType === 'SERVICE' && values.serviceType === 'CONVERSATIONAL'
      ? 'Per-conversation cap'
      : 'Max per run'

  const parentHint = useMemo(() => {
    if (parentLimit === undefined || parentLimit === null) return null
    return `Max: ${formatAmount(parentLimit, values.resourceType)}${parentScopeLabel ? ` (from ${parentScopeLabel})` : ''}`
  }, [parentLimit, parentScopeLabel, values.resourceType])

  const isDisabled = (field: keyof BudgetFormValues) => disabledFields?.includes(field) ?? false

  const update = (patch: Partial<BudgetFormValues>) => onChange({ ...values, ...patch })

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit()
      }}
      className="space-y-6"
    >
      {error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
          {error}
        </div>
      )}

      <div className="space-y-4 rounded-lg border p-4">
        <h3 className="font-medium">Scope</h3>

        <div className="grid gap-2">
          <Label htmlFor="scopeType">Scope type</Label>
          <Select
            value={values.scopeType}
            onValueChange={(v) => update({ scopeType: v as QuotaScope })}
            disabled={mode === 'edit' || isDisabled('scopeType')}
          >
            <SelectTrigger id="scopeType">
              <SelectValue placeholder="Select scope type" />
            </SelectTrigger>
            <SelectContent>
              {SCOPE_OPTIONS.map((opt) => {
                // Governance: BUDGET_OVERRIDE — disable PROJECT/SERVICE when the
                // profile doesn't permit non-org overrides (threshold: PER_SERVICE).
                const governanceDisabled = !budgetOverrideAllowed
                  && (opt.value === 'PROJECT' || opt.value === 'SERVICE')
                return (
                  <SelectItem
                    key={opt.value}
                    value={opt.value}
                    disabled={governanceDisabled}
                  >
                    {opt.label}
                    {governanceDisabled && governanceProfileName && (
                      <span className="text-xs text-muted-foreground ml-2">
                        (locked by {governanceProfileName} profile)
                      </span>
                    )}
                  </SelectItem>
                )
              })}
            </SelectContent>
          </Select>
        </div>

        <div className="grid gap-2">
          <Label htmlFor="scopeId">Scope ID</Label>
          <Input
            id="scopeId"
            value={values.scopeId}
            onChange={(e) => update({ scopeId: e.target.value })}
            disabled={mode === 'edit' || isDisabled('scopeId')}
            placeholder="e.g. project uuid"
          />
        </div>

        {values.scopeType === 'SERVICE' && (
          <div className="grid gap-2">
            <Label htmlFor="serviceType">Service type</Label>
            <Select
              value={values.serviceType || 'WORKFLOW'}
              onValueChange={(v) => update({ serviceType: v as QuotaServiceType | '' })}
              disabled={isDisabled('serviceType')}
            >
              <SelectTrigger id="serviceType">
                <SelectValue placeholder="Select service type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="WORKFLOW">Workflow</SelectItem>
                <SelectItem value="CONVERSATIONAL">Conversational</SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}
      </div>

      <div className="space-y-4 rounded-lg border p-4">
        <h3 className="font-medium">Budget</h3>

        <div className="grid grid-cols-2 gap-4">
          <div className="grid gap-2">
            <Label htmlFor="resourceType">Resource</Label>
            <Select
              value={values.resourceType}
              onValueChange={(v) => update({ resourceType: v as QuotaResourceType })}
              disabled={mode === 'edit' || isDisabled('resourceType')}
            >
              <SelectTrigger id="resourceType">
                <SelectValue placeholder="Select resource" />
              </SelectTrigger>
              <SelectContent>
                {RESOURCE_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>{opt.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid gap-2">
            <Label htmlFor="period">Period</Label>
            <Select
              value={values.period}
              onValueChange={(v) => update({ period: v as QuotaPeriod })}
              disabled={mode === 'edit' || isDisabled('period')}
            >
              <SelectTrigger id="period">
                <SelectValue placeholder="Select period" />
              </SelectTrigger>
              <SelectContent>
                {PERIOD_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>{opt.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="grid gap-2">
          <Label htmlFor="limit">Limit</Label>
          <Input
            id="limit"
            type="number"
            min={0}
            value={values.limitAmount}
            onChange={(e) => update({ limitAmount: e.target.value })}
            placeholder={values.resourceType === 'COST_USD_CENTS' ? 'e.g. 10000 = $100.00' : 'e.g. 100000'}
            data-testid="quota-limit-input"
          />
          {parentHint && <p className="text-xs text-muted-foreground">{parentHint}</p>}
        </div>

        {showType && (
          <div className="grid gap-2">
            <Label>Type</Label>
            <div className="flex items-center gap-4">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name="quotaType"
                  value="CEILING"
                  checked={values.quotaType === 'CEILING'}
                  onChange={() => update({ quotaType: 'CEILING' })}
                />
                Ceiling
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name="quotaType"
                  value="RESERVATION"
                  checked={values.quotaType === 'RESERVATION'}
                  onChange={() => update({ quotaType: 'RESERVATION' })}
                />
                Reservation
              </label>
            </div>
          </div>
        )}

        <div className="grid gap-2">
          <Label>Enforcement</Label>
          <div className="flex items-center gap-4">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="enforcementMode"
                value="TELEMETRY"
                checked={values.enforcementMode === 'TELEMETRY'}
                onChange={() => update({ enforcementMode: 'TELEMETRY' })}
              />
              Telemetry
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="enforcementMode"
                value="WARN"
                checked={values.enforcementMode === 'WARN'}
                onChange={() => update({ enforcementMode: 'WARN' })}
              />
              Warn
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="enforcementMode"
                value="BLOCK"
                checked={values.enforcementMode === 'BLOCK'}
                onChange={() => update({ enforcementMode: 'BLOCK' })}
              />
              Block
            </label>
          </div>
        </div>

        {showMaxPerRun && (
          <div className="grid gap-2">
            <Label htmlFor="maxExecutionAmount">{perRunLabel}</Label>
            <Input
              id="maxExecutionAmount"
              type="number"
              min={0}
              value={values.maxExecutionAmount}
              onChange={(e) => update({ maxExecutionAmount: e.target.value })}
              placeholder="Optional per-run limit"
            />
          </div>
        )}

        <div className="grid gap-2">
          <Label htmlFor="tags">Tags (JSON object)</Label>
          <Textarea
            id="tags"
            value={values.tagsJson}
            onChange={(e) => update({ tagsJson: e.target.value })}
            placeholder='{"cost-center": "engineering"}'
            rows={3}
          />
        </div>
      </div>

      <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting} data-testid="quota-save-button">
          {isSubmitting ? 'Saving...' : primaryLabel}
        </Button>
      </div>
    </form>
  )
}
