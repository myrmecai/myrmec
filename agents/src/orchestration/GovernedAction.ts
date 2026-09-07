// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The §7.2 governed-action and approval contracts — the runner-facing
 * types for HITL suspension (design §17.4). Transport-independent: the
 * executor maps these onto the §16.3 wire frames through the durable
 * outbox.
 */

/** The governed action kinds that can require human approval (§7.2). */
export type GovernedActionType =
  | "WORKER_TOOL"
  | "CHECKPOINT"
  | "PUSH"
  | "CONTINUE_BUDGET";

/** One pending action the runner wants to execute (§7.2). */
export interface GovernedAction {
  actionId: string;
  type: GovernedActionType;
  riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";
  summary: string;
  digest: string;
}

/** The §7.2 approval request the runner publishes before suspending. */
export interface OrchestrationApprovalRequest {
  schemaVersion: "1.0";
  approvalRequestId: string;
  dispatch: import("./types.js").DispatchIdentity;
  action: GovernedAction;
  snapshotRef?: string;
  snapshotTreeHash: string;
  stateDigest: string;
  expiresAt: string;
}

/**
 * The runner's approval sink (§17.4): when the decision is
 * REQUIRE_APPROVAL the runner must have one — its absence fails closed.
 * `request` MUST durably accept the request before the PAUSED result is
 * returned (at-least-once; the engine dedups by approvalRequestId).
 */
export interface OrchestrationApprovalSink {
  request(approval: OrchestrationApprovalRequest): Promise<void>;
}