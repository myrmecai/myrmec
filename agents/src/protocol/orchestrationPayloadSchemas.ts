// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Orchestration payload schemas (unified session execution design, SDK
 * touchpoint 8): the retained zod schemas that validate payloads INSIDE
 * unified `execution.*` frames. The legacy envelope builders
 * (`inference.accept`, `orchestration.event/result/approval_requested`,
 * `workspace.release_ack`) are deleted with the legacy wire family - the
 * contract is the CONTENT STRUCTURE: field names, nesting, types,
 * validated with strict zod before any workspace acquisition or model
 * call.
 */
import { z } from "zod";

// -- shared shapes ---------------------------------------------------

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

export const usageSchema = z
  .object({
    helperCalls: z.number().int().nonnegative(),
    rejectionCount: z.number().int().nonnegative(),
    totalTokens: z.number().int().nonnegative(),
  })
  .strict();
export type UsageWire = z.infer<typeof usageSchema>;

// -- HITL (execution.approval.requested / execution.paused) ---------

export const governedActionSchema = z
  .object({
    actionId: uuid,
    type: z.string().min(1),
    riskClass: z.enum(["SAFE", "DESTRUCTIVE", "IRREVERSIBLE"]),
    summary: z.string().max(2000),
    digest: sha256Hex,
  })
  .strict();

// -- continuation records (execution.failed retryable / paused) ------

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

// -- the redacted orchestration result (result.structured) -----------

export const orchestrationResultPayloadSchema = z
  .object({
    schemaVersion: z.literal("1.0"),
    resultId: uuid,
    resultDigest: sha256Hex,
    dispatch: dispatchIdentitySchema,
    status: z.enum(["COMPLETED", "FAILED", "CANCELLED", "PAUSED"]),
    retryDisposition: z.enum(["NONE", "RETRYABLE", "TERMINAL"]),
    summary: z.string(),
    helperCalls: z.array(z.record(z.string(), z.unknown())).default([]),
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