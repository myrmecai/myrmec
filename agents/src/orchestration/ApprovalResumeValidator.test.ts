// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ApprovalResumeValidator tests (design §7/§17.4, HITL slice C): every
 * field of the typed ApprovalDecision binds the restored suspension —
 * identity, status, digests, generation, expiry — and any mismatch,
 * expired decision, or replay against another dispatch fails closed.
 */
import { describe, expect, it } from "vitest";
import {
  validateApprovalResume,
  type ApprovalDecisionEnvelope,
  type RestoredSuspension,
} from "./ApprovalResumeValidator.js";

const ACTION_DIGEST = "a".repeat(64);
const STATE_DIGEST = "b".repeat(64);
const TREE = "c".repeat(40);

function decision(over: Partial<ApprovalDecisionEnvelope> = {}): ApprovalDecisionEnvelope {
  return {
    schemaVersion: "1.0",
    decisionId: "decision-1",
    approvalRequestId: "11111111-1111-4111-8111-111111111111",
    continuationId: "cont-1",
    previousDispatchId: "22222222-2222-4222-8222-222222222222",
    status: "APPROVED",
    actionDigest: ACTION_DIGEST,
    stateDigest: STATE_DIGEST,
    snapshotTreeHash: TREE,
    workspaceGeneration: 2,
    decidedAt: "2026-01-01T00:00:00Z",
    expiresAt: "2999-01-01T00:00:00Z",
    ...over,
  };
}

function restored(over: Partial<RestoredSuspension["suspension"]> = {}): RestoredSuspension {
  return {
    continuationId: "cont-1",
    previousDispatchId: "22222222-2222-4222-8222-222222222222",
    suspension: {
      continuationId: "cont-1",
      continuationRef: "local:cont-1",
      snapshotTreeHash: TREE,
      workspaceRevision: 2,
      stateDigest: STATE_DIGEST,
      reason: "HITL_APPROVAL",
      approvalRequestId: "11111111-1111-4111-8111-111111111111",
      pendingAction: {
        actionId: "action-1",
        type: "WORKER_TOOL",
        riskClass: "DESTRUCTIVE",
        summary: "worker:impl:edit",
        digest: ACTION_DIGEST,
      },
      expiresAt: "2999-01-01T00:00:00Z",
      ...over,
    },
  };
}

describe("ApprovalResumeValidator", () => {
  it("accepts a decision that binds the restored suspension exactly", () => {
    const result = validateApprovalResume(decision(), restored());
    expect(result.valid).toBe(true);
    expect(result.rejection).toBeUndefined();
  });

  it("rejects a decision for another continuation (replay protection)", () => {
    const result = validateApprovalResume(
      decision({ continuationId: "cont-other" }),
      restored(),
    );
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("continuationId");
  });

  it("rejects a decision replayed against another dispatch", () => {
    const result = validateApprovalResume(
      decision({ previousDispatchId: "99999999-9999-4999-8999-999999999999" }),
      restored(),
    );
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("previousDispatchId");
  });

  it("rejects a decision whose approval request differs from the suspension", () => {
    const result = validateApprovalResume(
      decision({ approvalRequestId: "33333333-3333-4333-8333-333333333333" }),
      restored(),
    );
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("approvalRequestId");
  });

  it("rejects a non-APPROVED status with APPROVAL_REJECTED", () => {
    const result = validateApprovalResume(decision({ status: "REJECTED" }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_REJECTED");
  });

  it("rejects a state-digest mismatch (an older proposal cannot authorize a new one)", () => {
    const result = validateApprovalResume(decision({ stateDigest: "d".repeat(64) }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("stateDigest");
  });

  it("rejects a snapshot-tree mismatch", () => {
    const result = validateApprovalResume(decision({ snapshotTreeHash: "e".repeat(40) }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("snapshotTreeHash");
  });

  it("rejects an action-digest mismatch against the pending action", () => {
    const result = validateApprovalResume(decision({ actionDigest: "f".repeat(64) }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("actionDigest");
  });

  it("rejects when the suspension lost its pending action", () => {
    const result = validateApprovalResume(decision(), restored({ pendingAction: undefined }));
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("actionDigest");
  });

  it("rejects a workspace-generation mismatch", () => {
    const result = validateApprovalResume(decision({ workspaceGeneration: 3 }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("workspaceGeneration");
  });

  it("rejects an expired decision with APPROVAL_EXPIRED", () => {
    const result = validateApprovalResume(
      decision({ expiresAt: "2020-01-01T00:00:00Z" }),
      restored(),
      new Date("2026-06-01T00:00:00Z"),
    );
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_EXPIRED");
  });

  it("rejects a malformed expiry timestamp", () => {
    const result = validateApprovalResume(decision({ expiresAt: "not-a-time" }), restored());
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_EXPIRED");
  });

  it("accepts at the exact expiry boundary minus one millisecond", () => {
    const result = validateApprovalResume(
      decision({ expiresAt: "2026-06-01T00:00:00.000Z" }),
      restored(),
      new Date("2026-05-31T23:59:59.999Z"),
    );
    expect(result.valid).toBe(true);
  });

  it("rejects when the durable manifest identity drifted from the suspension", () => {
    const withManifest: RestoredSuspension = {
      ...restored(),
      manifest: {
        continuationId: "cont-other",
        dispatchId: "d1",
        attemptOrdinal: 1,
        budgetCounters: {
          workerCalls: 0,
          totalTokens: 0,
          rejectionCount: 0,
        },
        completedCallIds: [],
        candidateTreeHash: TREE,
        workspaceRevision: 2,
        verifierHistory: [],
        createdAt: "2026-01-01T00:00:00Z",
        stateDigest: STATE_DIGEST,
      },
    };
    const result = validateApprovalResume(decision(), withManifest);
    expect(result.valid).toBe(false);
    expect(result.rejection).toBe("APPROVAL_POLICY_DENIED");
    expect(result.reason).toContain("manifest");
  });

  it("accepts when the manifest agrees with the suspension", () => {
    const withManifest: RestoredSuspension = {
      ...restored(),
      manifest: {
        continuationId: "cont-1",
        dispatchId: "22222222-2222-4222-8222-222222222222",
        attemptOrdinal: 1,
        budgetCounters: {
          workerCalls: 0,
          totalTokens: 0,
          rejectionCount: 0,
        },
        completedCallIds: [],
        candidateTreeHash: TREE,
        workspaceRevision: 2,
        verifierHistory: [],
        createdAt: "2026-01-01T00:00:00Z",
        stateDigest: STATE_DIGEST,
      },
    };
    const result = validateApprovalResume(decision(), withManifest);
    expect(result.valid).toBe(true);
  });
});