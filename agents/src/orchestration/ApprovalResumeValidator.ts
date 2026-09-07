// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ApprovalResumeValidator (design §7/§17.4, HITL slice C): validates the
 * typed {@code ApprovalDecision} a HITL-resume continuation carries
 * against the restored §7.3 suspension record BEFORE the runner resumes
 * the exact pending action.
 *
 * <p>Fail-closed surface (§7: "Any mismatch, expired decision, or
 * decision replay against another dispatch fails closed"):</p>
 * <ul>
 *   <li>identity — decision.continuationId/previousDispatchId bind the
 *       restored suspension and the suspended dispatch;</li>
 *   <li>digests — stateDigest, snapshotTreeHash, and the pending
 *       action's digest must match the stored suspension (a decision
 *       for an older proposal cannot authorize a new one);</li>
 *   <li>status — only APPROVED authorizes a resume (REJECTED never
 *       reaches the agent as a dispatch in V1 — the engine consumes it —
 *       but the agent still fails closed if it does);</li>
 *   <li>expiry — the decision's expiresAt must still be in the future;</li>
 *   <li>workspace generation — the restored suspension's revision must
 *       match the decision's workspaceGeneration.</li>
 * </ul>
 */
import type { ContinuationManifest } from "./ContinuationStateStore.js";
import type { ApprovalDecisionEnvelope, SuspensionRecord } from "./types.js";

/** The typed §7 ApprovalDecision carried by a resume continuation. */
export type { ApprovalDecisionEnvelope };

/** Why a resume was refused — maps to the terminal wire error codes. */
export type ResumeRejection =
  | "APPROVAL_REJECTED"
  | "APPROVAL_EXPIRED"
  | "APPROVAL_POLICY_DENIED";

export interface ResumeValidation {
  valid: boolean;
  rejection?: ResumeRejection;
  reason?: string;
}

/** The restored suspension a decision is validated against. */
export interface RestoredSuspension {
  continuationId: string;
  previousDispatchId: string;
  suspension: SuspensionRecord;
  manifest?: ContinuationManifest;
}

/** Validates one decision envelope against the restored suspension. */
export function validateApprovalResume(
  decision: ApprovalDecisionEnvelope,
  restored: RestoredSuspension,
  now: Date = new Date(),
): ResumeValidation {
  // §7 identity: the decision binds the exact continuation + prior
  // dispatch that were suspended. Any drift fails closed — a decision
  // replay against another dispatch must never authorize it.
  if (decision.continuationId !== restored.continuationId) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.continuationId does not match the restored suspension",
    };
  }
  if (decision.previousDispatchId !== restored.previousDispatchId) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.previousDispatchId does not match the suspended dispatch",
    };
  }
  if (decision.approvalRequestId !== (restored.suspension.approvalRequestId ?? "")) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.approvalRequestId does not match the suspension's request",
    };
  }

  // §7 status: only APPROVED authorizes resume.
  if (decision.status !== "APPROVED") {
    return {
      valid: false,
      rejection: "APPROVAL_REJECTED",
      reason: "the decision is not an approval",
    };
  }

  // §7 digests: the stored state + tree + pending action must still be
  // the exact proposal the human approved.
  if (decision.stateDigest !== restored.suspension.stateDigest) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.stateDigest does not match the suspension state",
    };
  }
  if (decision.snapshotTreeHash !== restored.suspension.snapshotTreeHash) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.snapshotTreeHash does not match the suspension tree",
    };
  }
  const pendingDigest = restored.suspension.pendingAction?.digest;
  if (!pendingDigest || decision.actionDigest !== pendingDigest) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.actionDigest does not match the pending action",
    };
  }

  // §7 workspace generation: the checkout generation the human approved.
  if (decision.workspaceGeneration !== restored.suspension.workspaceRevision) {
    return {
      valid: false,
      rejection: "APPROVAL_POLICY_DENIED",
      reason: "decision.workspaceGeneration does not match the suspension revision",
    };
  }

  // §7 expiry: the decision's own deadline must still be live. A
  // decision delivered past its expiry fails closed as APPROVAL_EXPIRED.
  const expiresAtMs = Date.parse(decision.expiresAt);
  if (!Number.isFinite(expiresAtMs)) {
    return {
      valid: false,
      rejection: "APPROVAL_EXPIRED",
      reason: "decision.expiresAt is not a valid timestamp",
    };
  }
  if (expiresAtMs <= now.getTime()) {
    return {
      valid: false,
      rejection: "APPROVAL_EXPIRED",
      reason: "the approval decision expired before resume",
    };
  }

  // The manifest (when present — the durable continuation store) must
  // agree with the suspension identity: a regenerated or corrupt
  // manifest never authorizes the stored pending action.
  if (restored.manifest) {
    if (restored.manifest.continuationId !== restored.continuationId) {
      return {
        valid: false,
        rejection: "APPROVAL_POLICY_DENIED",
        reason: "the continuation manifest identity drifted",
      };
    }
    if (restored.manifest.candidateTreeHash !== restored.suspension.snapshotTreeHash) {
      return {
        valid: false,
        rejection: "APPROVAL_POLICY_DENIED",
        reason: "the continuation manifest tree drifted from the suspension",
      };
    }
  }

  return { valid: true };
}