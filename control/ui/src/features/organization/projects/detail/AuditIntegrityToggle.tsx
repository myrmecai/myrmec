// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { projectSettingApi } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { useState } from 'react'

/**
 * Toggle for enabling/disabling tamper-evident (hash-chained) audit logging
 * for a project.
 *
 * Under the STRICT governance profile, the server rejects changes with
 * `GOVERNANCE_VIOLATION` (403) — the toggle stays visible but an error
 * toast is shown. Under STANDARD/FLEXIBLE, the project setting
 * `audit_integrity_enabled` controls whether chaining is active.
 */
export function AuditIntegrityToggle({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const { data: settings } = useQuery({
    queryKey: ['project-settings', projectId],
    queryFn: () => projectSettingApi.list(projectId),
  })

  const currentSetting = settings?.find((s) => s.settingKey === 'audit_integrity_enabled')
  const isEnabled = currentSetting?.settingValue === 'true'

  const toggleMutation = useMutation({
    mutationFn: (value: boolean) =>
      projectSettingApi.update(projectId, 'audit_integrity_enabled', String(value)),
    onSuccess: () => {
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['project-settings', projectId] })
    },
    onError: (e: any) => {
      const msg = e?.message ?? 'Failed to update audit integrity setting'
      // GOVERNANCE_VIOLATION under STRICT profile
      if (msg.includes('GOVERNANCE_VIOLATION') || msg.includes('403')) {
        setError('Always on under Strict governance profile. Cannot disable.')
      } else {
        setError(msg)
      }
    },
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Audit Integrity</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center gap-3">
          <input
            type="checkbox"
            id="audit-integrity-toggle"
            checked={isEnabled}
            disabled={toggleMutation.isPending}
            onChange={(e) => toggleMutation.mutate(e.target.checked)}
            className="h-4 w-4"
          />
          <Label htmlFor="audit-integrity-toggle" className="cursor-pointer">
            Tamper-evident audit log (hash-chained events)
          </Label>
        </div>
        <p className="text-sm text-muted-foreground">
          When enabled, every audit event in this project is cryptographically
          chained using HMAC-SHA256. Any tampering with a past event is
          detectable via the verify endpoint.
        </p>
        {error && (
          <p className="text-sm text-red-500">{error}</p>
        )}
      </CardContent>
    </Card>
  )
}