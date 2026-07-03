// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import type { InstructionCategory } from '@/lib/api'

export const CATEGORY_LABELS: Record<InstructionCategory, string> = {
  PERSONA: 'Persona',
  STANDARD: 'Standard',
  REQUIREMENT: 'Requirement',
  ARCHITECTURE: 'Architecture',
  SECURITY: 'Security',
  COMPLIANCE: 'Compliance',
  SAFETY: 'Safety',
  GOAL: 'Goal',
  OUTPUT_FORMAT: 'Output Format',
  CONSTRAINT: 'Constraint',
  DOMAIN_RULE: 'Domain Rule',
  RESPONSE_STYLE: 'Response Style',
}

export const STATUS_COLORS: Record<string, string> = {
  INCOMPLETE: 'bg-yellow-500',
  ACTIVE: 'bg-green-500',
  DISABLED: 'bg-gray-500',
  ARCHIVED: 'bg-red-500',
}