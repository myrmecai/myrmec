// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Shared constants for the AI Infrastructure Models feature.
 *
 * Status display colours and deployment-type labels used by the Models list
 * and its sub-components.
 */

export const MODEL_STATUS_COLORS = {
  ACTIVE: 'text-green-600',
  INACTIVE: 'text-muted-foreground',
} as const

export const DEPLOYMENT_TYPE_LABELS = {
  CLOUD: 'Cloud',
  ON_PREMISE: 'On-Premise',
} as const

export const HEALTH_STATUS_COLORS = {
  HEALTHY: 'text-green-500',
  DEGRADED: 'text-yellow-500',
  UNHEALTHY: 'text-red-500',
  LOADING: 'text-blue-500',
} as const