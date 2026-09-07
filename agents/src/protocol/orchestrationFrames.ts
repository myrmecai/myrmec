// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Orchestration wire frames (design §16.3, Feature 10). The contract is
 * the CONTENT STRUCTURE — field names, nesting, types — validated with
 * strict Zod schemas before any workspace acquisition or model call.
 * Ordinary inference frames stay untouched in inferenceFrames.ts.
 */
import { z } from "zod";
import { makeEnvelope, type Envelope } from "./envelope.js";

// ── shared shapes ───────────────────────────────────────────────────

const uuid = z.string().uuid();
const sha256Hex = z.string().regex(/^[0-9a-f]{64}$/, "sha-256 hex");

export const dispatchIdentitySchema = z
  .object({
    workflowId: uuid,
    runId: uuid,
    stepId: z.string().min(1),
    taskId: uuid,
    attemptId: uuid,
    attemptOrdinal: z.number().int().positive(),
    dispatchId: uuid,
    continuationId: z.string().min(1).optional(),
  })
  .strict();
export type DispatchIdentityWire = z.infer<typeof dispatchIdentitySchema>;

const usageSchema = z
  .object({
    workerCalls: z.number().int().nonnegative(),
    rejectionCount: z.number().int().nonnegative(),
    totalTokens: z.number().int().nonnegative(),
  })
  .strict();
export type UsageWire = z.infer<typeof usageSchema>;

// ── inference.accept (orchestration admission) ──────────────────────

export const inferenceAcceptPayloadSchema = z
  .object({
    requestId: uuid,
    sessionId: uuid.nullable(),
    dispatchId: uuid,
    assignmentDigest: sha256Hex,
  })
  .strict();
export type InferenceAcceptWire = z.infer<typeof inferenceAcceptPayloadSchema>;

// ── orchestration.event (Agent → Engine) ─────────────────────────────

export const orchestrationEventPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    eventId: uuid,
    dispatch: dispatchIdentitySchema,
    type: z.string().min(1),
    sequence: z.number().int().nonnegative(),
    occurredAt: z.string().min(1),
    status: z.string().optional(),
    workerName: z.string().optional(),
    callId: uuid.optional(),
    candidateTreeHash: z.string().optional(),
    durationMs: z.number().int().nonnegative().optional(),
    usage: usageSchema.optional(),
  })
  .strict();
export type OrchestrationEventWire = z.infer<typeof orchestrationEventPayloadSchema>;

// ── orchestration.approval_requested (Agent → Engine) ───────────────

export const governedActionSchema = z
  .object({
    actionId: uuid,
    type: z.string().min(1),
    riskClass: z.enum(["SAFE", "DESTRUCTIVE", "IRREVERSIBLE"]),
    summary: z.string().max(2000),
    digest: sha256Hex,
  })
  .strict();

export const orchestrationApprovalRequestedPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    approvalRequestId: uuid,
    dispatch: dispatchIdentitySchema,
    action: governedActionSchema,
    snapshotRef: z.string().optional(),
    snapshotTreeHash: sha256Hex,
    stateDigest: sha256Hex,
    expiresAt: z.string().min(1),
  })
  .strict();
export type OrchestrationApprovalRequestedWire = z.infer<
  typeof orchestrationApprovalRequestedPayloadSchema
>;

// ── orchestration.result (Agent → Engine; one logical terminal) ──────

export const continuationRecordSchema = z
  .object({
    continuationId: z.string().min(1),
    continuationRef: z.string().min(1),
    snapshotRef: z.string().optional(),
    snapshotDigest: sha256Hex.optional(),
    snapshotTreeHash: z.string().min(1),
    workspaceRevision: z.number().int().nonnegative(),
    stateDigest: sha256Hex,
  })
  .strict();

export const suspensionRecordSchema = continuationRecordSchema.extend({
  reason: z.enum(["HITL_APPROVAL", "BUDGET_REVIEW"]),
  approvalRequestId: uuid.optional(),
  pendingAction: governedActionSchema.optional(),
  expiresAt: z.string().min(1).optional(),
});

export const orchestrationResultPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    resultId: uuid,
    resultDigest: sha256Hex,
    dispatch: dispatchIdentitySchema,
    status: z.enum(["COMPLETED", "FAILED", "CANCELLED", "PAUSED"]),
    retryDisposition: z.enum(["NONE", "RETRYABLE", "TERMINAL"]),
    summary: z.string(),
    workerCalls: z.array(z.record(z.string(), z.unknown())).default([]),
    verifierResults: z.array(z.record(z.string(), z.unknown())).default([]),
    commandExecutions: z.array(z.record(z.string(), z.unknown())).default([]),
    changedFiles: z.array(z.string()).default([]),
    commits: z.array(z.record(z.string(), z.unknown())).default([]),
    cleanWorktree: z.boolean(),
    usage: usageSchema,
    continuation: continuationRecordSchema.optional(),
    suspension: suspensionRecordSchema.optional(),
    errorCode: z.string().optional(),
  })
  .strict();
export type OrchestrationResultWire = z.infer<typeof orchestrationResultPayloadSchema>;

// ── orchestration.release (Engine → Agent) ──────────────────────────

export const orchestrationReleasePayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0").optional(),
    releaseId: uuid,
    runId: uuid,
    workspaceGeneration: z.number().int().nonnegative(),
    reason: z.string().max(500),
  })
  .strict();
export type OrchestrationReleaseWire = z.infer<typeof orchestrationReleasePayloadSchema>;

// ── workspace release acknowledgement (Supervisor → Engine) ────────

export const workspaceReleaseAcknowledgementPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    acknowledgementId: uuid,
    releaseId: uuid,
    runId: uuid,
    workflowId: uuid,
    workspaceGeneration: z.number().int().nonnegative(),
    status: z.enum(["RELEASED", "LOST", "CLEANUP_FAILED"]),
    occurredAt: z.string().min(1),
  })
  .strict();
export type WorkspaceReleaseAcknowledgementWire = z.infer<
  typeof workspaceReleaseAcknowledgementPayloadSchema
>;

// ── orchestration.budget_updated (Engine → Agent; tighten-only) ─────

export const orchestrationBudgetUpdatedPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    dispatch: dispatchIdentitySchema,
    usage: usageSchema,
    allowance: z.object({ maxTokens: z.number().int().positive() }).strict(),
  })
  .strict();
export type OrchestrationBudgetUpdatedWire = z.infer<
  typeof orchestrationBudgetUpdatedPayloadSchema
>;

// ── frame constructors ──────────────────────────────────────────────

export function inferenceAccept(detail: {
  requestId: string;
  sessionId: string | null;
  dispatchId: string;
  assignmentDigest: string;
}): Envelope {
  return makeEnvelope("inference.accept", {
    requestId: detail.requestId,
    sessionId: detail.sessionId,
    dispatchId: detail.dispatchId,
    assignmentDigest: detail.assignmentDigest,
  });
}

export function orchestrationEvent(detail: OrchestrationEventWire): Envelope {
  return makeEnvelope("orchestration.event", detail);
}

export function orchestrationApprovalRequested(
  detail: OrchestrationApprovalRequestedWire,
): Envelope {
  return makeEnvelope("orchestration.approval_requested", detail);
}

export function orchestrationResult(detail: OrchestrationResultWire): Envelope {
  return makeEnvelope("orchestration.result", detail);
}

export function workspaceReleaseAcknowledgement(
  detail: WorkspaceReleaseAcknowledgementWire,
): Envelope {
  return makeEnvelope("host.announce", detail);
}