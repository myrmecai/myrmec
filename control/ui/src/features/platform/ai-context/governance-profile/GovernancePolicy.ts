// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Pure, testable governance-policy helper.
 *
 * Builds a `Map<featureCode, { possibleValues, currentValues }>` from the
 * `governanceApi.getCurrent()` response and exposes membership + ordinal
 * threshold checks. Mirrors the backend `EffectivePolicy.allows` /
 * `permitsAtLeast` semantics exactly — the UI hides exactly what the
 * backend would reject.
 *
 * **Multi-value features** (`INSTRUCTION_SOURCES`, `KNOWLEDGE_PROVIDERS`,
 * `DATA_FEEDS`) use `allows` (set membership).
 *
 * **Threshold features** (`INLINE_INSTRUCTIONS_SCOPE`, `BUDGET_OVERRIDE`)
 * use `permitsAtLeast` (ordinal: `indexOf(currentValue) >= indexOf(required)`
 * in `possibleValues`, ordered strict→loose). This prevents FLEXIBLE
 * from falsely disabling: `ALL` permits `PROJECT_SERVICE`,
 * `CONFIGURABLE` permits `PER_SERVICE`.
 *
 * Unknown feature/value → treated as **not allowed** (fail safe).
 */
export class GovernancePolicy {
  private readonly features: Map<string, { possibleValues: string[]; currentValues: string[] }>
  readonly profileCode: string
  readonly profileName: string

  constructor(profile: {
    code: string
    name: string
    groups: { features: { code: string; possibleValues: string[]; currentValues: string[] }[] }[]
  }) {
    this.profileCode = profile.code
    this.profileName = profile.name
    this.features = new Map()
    for (const group of profile.groups ?? []) {
      for (const f of group.features ?? []) {
        this.features.set(f.code, {
          possibleValues: f.possibleValues ?? [],
          currentValues: f.currentValues ?? [],
        })
      }
    }
  }

  /** All current values for a feature (multi-value features return >1). */
  values(feature: string): string[] {
    return this.features.get(feature)?.currentValues ?? []
  }

  /** The single current value for a single-value feature. */
  single(feature: string): string {
    const v = this.values(feature)
    if (v.length === 0) {
      console.warn(`[GovernancePolicy] No value for feature '${feature}' in profile '${this.profileCode}'`)
      return ''
    }
    return v[0]
  }

  /** Whether a value is allowed for the given feature (set membership). */
  allows(feature: string, value: string): boolean {
    const f = this.features.get(feature)
    if (!f) {
      console.warn(`[GovernancePolicy] Unknown feature '${feature}' — treating as not allowed`)
      return false
    }
    return f.currentValues.includes(value)
  }

  /**
   * Whether the profile's single-value setting permits at least the given
   * threshold. Uses `possibleValues` ordering (strict→loose):
   * `indexOf(currentValue) >= indexOf(required)`.
   *
   * For threshold features like `INLINE_INSTRUCTIONS_SCOPE`
   * (`NONE < PROJECT_SERVICE < ALL`) and `BUDGET_OVERRIDE`
   * (`NONE < PER_SERVICE < CONFIGURABLE`).
   */
  permitsAtLeast(feature: string, required: string): boolean {
    const f = this.features.get(feature)
    if (!f || f.currentValues.length === 0) {
      console.warn(`[GovernancePolicy] Unknown/empty feature '${feature}' — treating as not permitted`)
      return false
    }
    const currentValue = f.currentValues[0]
    const profileIdx = f.possibleValues.indexOf(currentValue)
    const requiredIdx = f.possibleValues.indexOf(required)
    if (profileIdx < 0 || requiredIdx < 0) {
      console.warn(`[GovernancePolicy] Unknown value for '${feature}': current='${currentValue}', required='${required}'`)
      return false
    }
    return profileIdx >= requiredIdx
  }

  /**
   * Unified gate: returns `{ allowed: boolean; reason?: string }`.
   * Use `opts.ordinal: true` for threshold features.
   */
  gate(feature: string, value: string, opts?: { ordinal?: boolean }): {
    allowed: boolean
    reason?: string
  } {
    const allowed = opts?.ordinal
      ? this.permitsAtLeast(feature, value)
      : this.allows(feature, value)
    if (allowed) return { allowed: true }
    return {
      allowed: false,
      reason: `Not permitted by the "${this.profileName}" governance profile.`,
    }
  }
}