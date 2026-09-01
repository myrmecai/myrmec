// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Shared required-field markers for form labels.
 *
 * Semantics (project-wide convention):
 *   - Red asterisk (*)  → required to complete the action (non-versioned forms),
 *                         or required to PUBLISH (versioned resources).
 *   - Yellow asterisk (*) → required to SAVE A DRAFT (versioned resources).
 *
 * Versioned resources (Connection Configs, Instruction Assets, Knowledge
 * Providers, Assistants) go through a Draft → Publish lifecycle, so they
 * distinguish the two levels. Non-versioned forms have a single "required"
 * level and use the red asterisk only.
 */

/** Red asterisk — required to publish (versioned) or simply required (non-versioned). */
export const RequiredMark = () => (
  <span className="text-red-500 ml-0.5" title="Required" aria-hidden="true">
    *
  </span>
)

/** Yellow asterisk — required to save a draft (versioned resources only). */
export const DraftRequiredMark = () => (
  <span className="text-yellow-500 ml-0.5" title="Required to save draft" aria-hidden="true">
    *
  </span>
)