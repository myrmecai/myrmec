// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, expect, test } from "vitest";
import {
  dispatchIdentitySchema,
  inferenceAcceptPayloadSchema,
  orchestrationEventPayloadSchema,
  orchestrationReleasePayloadSchema,
  orchestrationResultPayloadSchema,
  workspaceReleaseAcknowledgementPayloadSchema,
} from "./orchestrationFrames.js";

// RFC 9562-valid fixtures (version nibble + variant bits), mirroring the
// engine-generated UUIDv5 identities these frames carry in production.
const workflowId = "11111111-1111-5111-8111-111111111111";
const runId = "22222222-2222-5222-8222-222222222222";
const taskId = "33333333-3333-5333-8333-333333333333";
const attemptId = "44444444-4444-5444-8444-444444444444";
const dispatchId = "55555555-5555-5555-8555-555555555555";

const dispatch = {
  workflowId,
  runId,
  stepId: "build",
  taskId,
  attemptId,
  attemptOrdinal: 1,
  dispatchId,
};

describe("orchestrationFrames (§16.3)", () => {
  test("a complete dispatch identity validates; a truncated one fails", () => {
    expect(dispatchIdentitySchema.safeParse(dispatch).success).toBe(true);
    const truncated = { ...dispatch };
    delete (truncated as { dispatchId?: string }).dispatchId;
    expect(dispatchIdentitySchema.safeParse(truncated).success).toBe(false);
  });

  test("inference.accept carries the exact admitted digest", () => {
    expect(
      inferenceAcceptPayloadSchema.safeParse({
        requestId: dispatch.attemptId,
        sessionId: null,
        dispatchId: dispatch.dispatchId,
        assignmentDigest: "a".repeat(64),
      }).success,
    ).toBe(true);
    expect(
      inferenceAcceptPayloadSchema.safeParse({
        requestId: dispatch.attemptId,
        sessionId: null,
        dispatchId: dispatch.dispatchId,
        assignmentDigest: "not-hex",
      }).success,
    ).toBe(false);
  });

  test("orchestration.event validates the §21 shape", () => {
    expect(
      orchestrationEventPayloadSchema.safeParse({
        schemaVersion: "1.0",
        eventId: "66666666-6666-4666-8666-666666666666",
        dispatch,
        type: "WORKER_STARTED",
        sequence: 17,
        occurredAt: new Date().toISOString(),
        workerName: "coder",
        usage: { workerCalls: 4, rejectionCount: 0, totalTokens: 8200 },
      }).success,
    ).toBe(true);
  });

  test("orchestration.result requires the terminal essentials", () => {
    const valid = {
      schemaVersion: "1.0",
      resultId: "77777777-7777-4777-8777-777777777777",
      resultDigest: "b".repeat(64),
      dispatch,
      status: "COMPLETED",
      retryDisposition: "NONE",
      summary: "all steps verified",
      workerCalls: [],
      verifierResults: [],
      commandExecutions: [],
      changedFiles: ["src/module.ts"],
      commits: [],
      cleanWorktree: true,
      usage: { workerCalls: 3, rejectionCount: 0, totalTokens: 1200 },
    };
    expect(orchestrationResultPayloadSchema.safeParse(valid).success).toBe(true);

    const failed = { ...valid, status: "UNKNOWN" as never };
    expect(orchestrationResultPayloadSchema.safeParse(failed).success).toBe(false);

    const pausedWithoutSuspension = { ...valid, status: "PAUSED" as const };
    // PAUSED without suspension is structurally allowed here — the outcome
    // service enforces the §17.4 requirement from the run result body.
    expect(orchestrationResultPayloadSchema.safeParse(pausedWithoutSuspension).success).toBe(true);
  });

  test("orchestration.release is the bounded §16.5 frame", () => {
    expect(
      orchestrationReleasePayloadSchema.safeParse({
        releaseId: "88888888-8888-4888-8888-888888888888",
        runId,
        workspaceGeneration: 2,
        reason: "TERMINAL_STATE",
      }).success,
    ).toBe(true);
    expect(
      orchestrationReleasePayloadSchema.safeParse({
        releaseId: "88888888-8888-4888-8888-888888888888",
        runId,
        workspaceGeneration: 2,
        reason: "r".repeat(501), // bounded
      }).success,
    ).toBe(false);
  });

  test("workspace release acknowledgement validates", () => {
    expect(
      workspaceReleaseAcknowledgementPayloadSchema.safeParse({
        schemaVersion: "1.0",
        acknowledgementId: "99999999-9999-4999-8999-999999999999",
        releaseId: "88888888-8888-4888-8888-888888888888",
        runId,
        workflowId,
        workspaceGeneration: 2,
        status: "RELEASED",
        occurredAt: new Date().toISOString(),
      }).success,
    ).toBe(true);
  });
});