// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { governanceApi, type GovernanceProfile, type FeatureGroupResponse } from '@/lib/api'
import { ContentAreaLayout } from '@/components/content-area-layout'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Shield, AlertCircle, Check } from 'lucide-react'

export function GovernanceProfilesList() {
  const { data: profiles, isLoading, error } = useQuery({
    queryKey: ['governance-profiles'],
    queryFn: governanceApi.list,
  })

  const [confirmProfile, setConfirmProfile] = useState<GovernanceProfile | null>(null)
  const queryClient = useQueryClient()

  const setDefaultMutation = useMutation({
    mutationFn: (code: string) => governanceApi.setDefault(code),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['governance-profiles'] })
      queryClient.invalidateQueries({ queryKey: ['governance-profile-current'] })
      setConfirmProfile(null)
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-muted-foreground">Loading...</p>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <AlertCircle className="h-8 w-8 text-destructive mx-auto mb-2" />
          <p className="text-destructive">Failed to load governance profiles</p>
        </div>
      </div>
    )
  }

  if (!profiles || profiles.length === 0) {
    return (
      <ContentAreaLayout>
        <p className="text-muted-foreground">No governance profiles found.</p>
      </ContentAreaLayout>
    )
  }

  // Collect all unique group codes in sortOrder
  const allGroups: FeatureGroupResponse[] = []
  for (const profile of profiles) {
    for (const group of profile.groups ?? []) {
      if (!allGroups.find((g) => g.code === group.code)) {
        allGroups.push(group)
      }
    }
  }
  allGroups.sort((a, b) => a.sortOrder - b.sortOrder)

  return (
    <ContentAreaLayout>
      <div className="space-y-6">
        {/* Header */}
        <div>
          <h1 className="text-2xl font-bold">Governance Profiles</h1>
          <p className="text-muted-foreground">
            Compare built-in governance profiles and select the org-level default.
            All projects inherit this profile and can only tighten it (ratchet enforcement).
          </p>
        </div>

        {/* Compare Matrix */}
        <div className="border rounded-lg overflow-hidden">
          <Table>
            <TableHeader>
              {/* Profile name row */}
              <TableRow>
                <TableHead className="w-[200px] sticky left-0 bg-card">
                  Profile
                </TableHead>
                {profiles.map((profile) => (
                  <TableHead
                    key={profile.code}
                    className={`border-l border-border ${profile.isCurrentDefault ? 'bg-green-100 dark:bg-green-900/40' : ''}`}
                  >
                    <div className="flex items-center gap-2">
                      <Shield className="h-4 w-4 text-primary" />
                      <span className="font-semibold">{profile.name}</span>
                      {profile.isCurrentDefault && (
                        <Check className="h-5 w-5 text-green-600 dark:text-green-400" />
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground mt-1 font-normal">
                      {profile.description}
                    </p>
                  </TableHead>
                ))}
              </TableRow>
              {/* Set As Default button row */}
              <TableRow>
                <TableHead className="sticky left-0 bg-card" />
                {profiles.map((profile) => (
                  <TableHead
                    key={profile.code}
                    className={`border-l border-border ${profile.isCurrentDefault ? 'bg-green-100 dark:bg-green-900/40' : ''}`}
                  >
                    <Button
                      size="sm"
                      variant={profile.isCurrentDefault ? 'secondary' : 'default'}
                      disabled={profile.isCurrentDefault || setDefaultMutation.isPending}
                      onClick={() => setConfirmProfile(profile)}
                    >
                      {profile.isCurrentDefault ? 'Current Default' : 'Set As Default'}
                    </Button>
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {allGroups.map((group) => (
                <GroupRows key={group.code} group={group} profiles={profiles} />
              ))}
            </TableBody>
          </Table>
        </div>
      </div>

      {/* Confirmation Dialog */}
      <Dialog open={confirmProfile !== null} onOpenChange={(open) => !open && setConfirmProfile(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Change Governance Profile</DialogTitle>
            <DialogDescription>
              Confirm the change of org-level governance profile.
            </DialogDescription>
          </DialogHeader>
          {confirmProfile && (
            <div className="space-y-3 py-2">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Current profile:</span>
                <span className="font-medium">
                  {profiles.find((p) => p.isCurrentDefault)?.name ?? 'Standard'}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">New profile:</span>
                <span className="font-medium">{confirmProfile.name}</span>
              </div>
              <p className="text-sm text-muted-foreground bg-muted/50 rounded-md p-3">
                This will immediately apply the new policies to all projects and services.
                Projects that are currently on a looser profile will need to be reviewed.
              </p>
              {setDefaultMutation.isError && (
                <p className="text-sm text-destructive">
                  Failed to update governance profile. Please try again.
                </p>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmProfile(null)}>
              Cancel
            </Button>
            <Button
              onClick={() => confirmProfile && setDefaultMutation.mutate(confirmProfile.code)}
              disabled={setDefaultMutation.isPending}
            >
              {setDefaultMutation.isPending ? 'Saving...' : 'Confirm'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </ContentAreaLayout>
  )
}

/**
 * Renders a group header row and one row per feature within the group.
 * Each cell shows the feature's current values for that profile.
 */
function GroupRows({
  group,
  profiles,
}: {
  group: FeatureGroupResponse
  profiles: GovernanceProfile[]
}) {
  const features = group.features

  return (
    <>
      {/* Group header row */}
      <TableRow className="bg-muted/50 hover:bg-muted/50">
        <TableCell colSpan={profiles.length + 1} className="font-semibold text-sm py-2">
          {group.description}
        </TableCell>
      </TableRow>
      {/* Feature rows */}
      {features.map((feature) => (
        <TableRow key={feature.code}>
          <TableCell className="font-medium text-sm sticky left-0 bg-card">
            {feature.description}
          </TableCell>
          {profiles.map((profile) => {
            const groupData = profile.groups?.find((g) => g.code === group.code)
            const featureData = groupData?.features.find((f) => f.code === feature.code)
            const values = featureData?.currentValues ?? []
            return (
              <TableCell
                key={profile.code}
                className={`border-l border-border ${profile.isCurrentDefault ? 'bg-green-100 dark:bg-green-900/40' : ''}`}
              >
                <div className="flex flex-wrap gap-1">
                  {values.length > 0 ? (
                    values.map((v) => (
                      <Badge key={v} variant="outline" className="text-xs">
                        {formatValue(v)}
                      </Badge>
                    ))
                  ) : (
                    <span className="text-muted-foreground text-xs">—</span>
                  )}
                </div>
              </TableCell>
            )
          })}
        </TableRow>
      ))}
    </>
  )
}

/**
 * Format raw enum values into more readable labels.
 */
function formatValue(value: string): string {
  return value
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}