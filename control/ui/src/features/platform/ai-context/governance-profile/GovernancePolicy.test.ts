// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, it, expect } from 'vitest'
import { GovernancePolicy } from './GovernancePolicy'
import type { GovernanceProfile } from '@/lib/api'

/** Fixture: a GovernanceProfile with the given feature values. */
function fixture(code: string, name: string, features: Record<string, { possibleValues: string[]; currentValues: string[] }>): GovernanceProfile {
  return {
    code,
    name,
    description: null,
    isCurrentDefault: false,
    groups: [
      {
        code: 'AI_CONTEXT',
        description: 'AI context',
        sortOrder: 10,
        features: Object.entries(features).map(([code, f]) => ({
          code,
          description: code,
          sortOrder: 0,
          possibleValues: f.possibleValues,
          currentValues: f.currentValues,
        })),
      },
    ],
  }
}

const STRICT = fixture('STRICT', 'Strict', {
  INSTRUCTION_SOURCES: { possibleValues: ['INLINE', 'GIT'], currentValues: ['GIT'] },
  INLINE_INSTRUCTIONS_SCOPE: { possibleValues: ['NONE', 'PROJECT_SERVICE', 'ALL'], currentValues: ['NONE'] },
  KNOWLEDGE_PROVIDERS: { possibleValues: ['MANAGED', 'EXTERNAL'], currentValues: ['MANAGED'] },
  DATA_FEEDS: { possibleValues: ['GIT', 'WEB_CRAWL', 'CONFLUENCE'], currentValues: ['GIT'] },
  BUDGET_OVERRIDE: { possibleValues: ['NONE', 'PER_SERVICE', 'CONFIGURABLE'], currentValues: ['NONE'] },
})

const STANDARD = fixture('STANDARD', 'Standard', {
  INSTRUCTION_SOURCES: { possibleValues: ['INLINE', 'GIT'], currentValues: ['INLINE', 'GIT'] },
  INLINE_INSTRUCTIONS_SCOPE: { possibleValues: ['NONE', 'PROJECT_SERVICE', 'ALL'], currentValues: ['PROJECT_SERVICE'] },
  KNOWLEDGE_PROVIDERS: { possibleValues: ['MANAGED', 'EXTERNAL'], currentValues: ['MANAGED', 'EXTERNAL'] },
  DATA_FEEDS: { possibleValues: ['GIT', 'WEB_CRAWL', 'CONFLUENCE'], currentValues: ['GIT', 'CONFLUENCE'] },
  BUDGET_OVERRIDE: { possibleValues: ['NONE', 'PER_SERVICE', 'CONFIGURABLE'], currentValues: ['PER_SERVICE'] },
})

const FLEXIBLE = fixture('FLEXIBLE', 'Flexible', {
  INSTRUCTION_SOURCES: { possibleValues: ['INLINE', 'GIT'], currentValues: ['INLINE', 'GIT'] },
  INLINE_INSTRUCTIONS_SCOPE: { possibleValues: ['NONE', 'PROJECT_SERVICE', 'ALL'], currentValues: ['ALL'] },
  KNOWLEDGE_PROVIDERS: { possibleValues: ['MANAGED', 'EXTERNAL'], currentValues: ['MANAGED', 'EXTERNAL'] },
  DATA_FEEDS: { possibleValues: ['GIT', 'WEB_CRAWL', 'CONFLUENCE'], currentValues: ['GIT', 'WEB_CRAWL', 'CONFLUENCE'] },
  BUDGET_OVERRIDE: { possibleValues: ['NONE', 'PER_SERVICE', 'CONFIGURABLE'], currentValues: ['CONFIGURABLE'] },
})

describe('GovernancePolicy', () => {
  // ── allows (membership) ──────────────────────────────────────────

  describe('allows (multi-value membership)', () => {
    it('STRICT allows GIT for INSTRUCTION_SOURCES', () => {
      expect(new GovernancePolicy(STRICT).allows('INSTRUCTION_SOURCES', 'GIT')).toBe(true)
    })
    it('STRICT does not allow INLINE for INSTRUCTION_SOURCES', () => {
      expect(new GovernancePolicy(STRICT).allows('INSTRUCTION_SOURCES', 'INLINE')).toBe(false)
    })
    it('STANDARD allows both INLINE and GIT', () => {
      const p = new GovernancePolicy(STANDARD)
      expect(p.allows('INSTRUCTION_SOURCES', 'INLINE')).toBe(true)
      expect(p.allows('INSTRUCTION_SOURCES', 'GIT')).toBe(true)
    })
    it('unknown feature returns false', () => {
      expect(new GovernancePolicy(STRICT).allows('UNKNOWN_FEATURE', 'X')).toBe(false)
    })
  })

  // ── permitsAtLeast (ordinal threshold) ──────────────────────────

  describe('permitsAtLeast (ordinal threshold)', () => {
    it('STRICT NONE does not permit PROJECT_SERVICE', () => {
      expect(new GovernancePolicy(STRICT).permitsAtLeast('INLINE_INSTRUCTIONS_SCOPE', 'PROJECT_SERVICE')).toBe(false)
    })
    it('STANDARD PROJECT_SERVICE permits PROJECT_SERVICE', () => {
      expect(new GovernancePolicy(STANDARD).permitsAtLeast('INLINE_INSTRUCTIONS_SCOPE', 'PROJECT_SERVICE')).toBe(true)
    })
    it('FLEXIBLE ALL permits PROJECT_SERVICE (the bug we fixed)', () => {
      expect(new GovernancePolicy(FLEXIBLE).permitsAtLeast('INLINE_INSTRUCTIONS_SCOPE', 'PROJECT_SERVICE')).toBe(true)
    })
    it('FLEXIBLE ALL permits ALL', () => {
      expect(new GovernancePolicy(FLEXIBLE).permitsAtLeast('INLINE_INSTRUCTIONS_SCOPE', 'ALL')).toBe(true)
    })
    it('STRICT NONE does not permit PER_SERVICE for BUDGET_OVERRIDE', () => {
      expect(new GovernancePolicy(STRICT).permitsAtLeast('BUDGET_OVERRIDE', 'PER_SERVICE')).toBe(false)
    })
    it('STANDARD PER_SERVICE permits PER_SERVICE', () => {
      expect(new GovernancePolicy(STANDARD).permitsAtLeast('BUDGET_OVERRIDE', 'PER_SERVICE')).toBe(true)
    })
    it('FLEXIBLE CONFIGURABLE permits PER_SERVICE (the bug we fixed)', () => {
      expect(new GovernancePolicy(FLEXIBLE).permitsAtLeast('BUDGET_OVERRIDE', 'PER_SERVICE')).toBe(true)
    })
    it('FLEXIBLE CONFIGURABLE permits CONFIGURABLE', () => {
      expect(new GovernancePolicy(FLEXIBLE).permitsAtLeast('BUDGET_OVERRIDE', 'CONFIGURABLE')).toBe(true)
    })
  })

  // ── gate ────────────────────────────────────────────────────────

  describe('gate', () => {
    it('returns allowed=true with no reason when permitted', () => {
      const result = new GovernancePolicy(STANDARD).gate('INSTRUCTION_SOURCES', 'INLINE')
      expect(result.allowed).toBe(true)
      expect(result.reason).toBeUndefined()
    })
    it('returns allowed=false with reason when not permitted', () => {
      const result = new GovernancePolicy(STRICT).gate('INSTRUCTION_SOURCES', 'INLINE')
      expect(result.allowed).toBe(false)
      expect(result.reason).toContain('Strict')
    })
    it('ordinal gate uses permitsAtLeast', () => {
      const result = new GovernancePolicy(FLEXIBLE).gate('INLINE_INSTRUCTIONS_SCOPE', 'PROJECT_SERVICE', { ordinal: true })
      expect(result.allowed).toBe(true)
    })
    it('ordinal gate rejects under STRICT', () => {
      const result = new GovernancePolicy(STRICT).gate('INLINE_INSTRUCTIONS_SCOPE', 'PROJECT_SERVICE', { ordinal: true })
      expect(result.allowed).toBe(false)
    })
  })

  // ── single / values ─────────────────────────────────────────────

  describe('single', () => {
    it('returns the first current value', () => {
      expect(new GovernancePolicy(STRICT).single('INLINE_INSTRUCTIONS_SCOPE')).toBe('NONE')
    })
    it('returns empty string for unknown feature', () => {
      expect(new GovernancePolicy(STRICT).single('UNKNOWN')).toBe('')
    })
  })

  describe('values', () => {
    it('returns all current values for multi-value features', () => {
      expect(new GovernancePolicy(STANDARD).values('INSTRUCTION_SOURCES')).toEqual(['INLINE', 'GIT'])
    })
    it('returns empty array for unknown feature', () => {
      expect(new GovernancePolicy(STRICT).values('UNKNOWN')).toEqual([])
    })
  })

  // ── profileCode / profileName ────────────────────────────────────

  it('exposes profileCode and profileName', () => {
    const p = new GovernancePolicy(STRICT)
    expect(p.profileCode).toBe('STRICT')
    expect(p.profileName).toBe('Strict')
  })
})