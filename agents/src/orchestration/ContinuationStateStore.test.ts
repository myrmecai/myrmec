// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * LocalContinuationStateStore + RecoveryStore tests (plan Feature 7,
 * design §13/§17.2): atomic publication, digest validation, same-
 * dispatch counter restoration, and terminal release.
 */
import { describe, it, expect, afterEach } from "vitest";
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  LocalContinuationStateStore,
  RecoveryStore,
  type ContinuationManifest,
} from "./ContinuationStateStore.js";
import { InMemoryBudgetController } from "./BudgetController.js";

let dirs: string[] = [];
function tmp(): string {
  const d = mkdtempSync(path.join(tmpdir(), `cont-store-`));
  dirs.push(d);
  return d;
}
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
  dirs = [];
});

function manifest(over: Partial<Omit<ContinuationManifest, "stateDigest">> = {}): Omit<ContinuationManifest, "stateDigest"> {
  return {
    continuationId: "cont-1",
    dispatchId: "dispatch-1",
    attemptOrdinal: 1,
    budgetCounters: { workerCalls: 2, totalTokens: 80, rejectionCount: 1 },
    completedCallIds: ["call-a", "call-b"],
    candidateTreeHash: "a".repeat(40),
    workspaceRevision: 2,
    verifierHistory: [
      {
        callId: "v1",
        workerName: "verifier",
        verdict: "REJECTED",
        summary: "bad",
        issues: ["x"],
        workspaceRevision: 1,
        candidateTreeHash: "a".repeat(40),
        attemptOrdinal: 1,
        sequence: 1,
      },
    ],
    createdAt: "2026-09-06T10:00:00.000Z",
    ...over,
  };
}

describe("LocalContinuationStateStore", () => {
  it("round-trips a manifest and restores the same-dispatch counters", () => {
    const store = new LocalContinuationStateStore(tmp());
    const written = store.put(manifest());
    const loaded = store.get("cont-1");
    expect(loaded).not.toBeNull();
    expect(loaded!.budgetCounters).toEqual({ workerCalls: 2, totalTokens: 80, rejectionCount: 1 });
    expect(loaded!.completedCallIds).toEqual(["call-a", "call-b"]);
    expect(loaded!.stateDigest).toBe(written.stateDigest);
    // A same-dispatch restart cannot reset the budget: the restored
    // counters re-exceed the original limits immediately (§13).
    const restored = new InMemoryBudgetController(
      { maxWorkerCalls: 2, maxTokens: 100, maxVerifierRejectionsPerAttempt: 1 },
      loaded!.budgetCounters,
    );
    expect(restored.tryReserveWorkerCall()?.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
  });

  it("fails closed on a digest mismatch — never partially restores", () => {
    const root = tmp();
    const store = new LocalContinuationStateStore(root);
    store.put(manifest());
    // Tamper with the persisted bytes.
    const file = path.join(root, "cont-1.json");
    const raw = JSON.parse(readFileSync(file, "utf-8")) as Record<string, unknown>;
    raw.budgetCounters = { workerCalls: 0, totalTokens: 0, rejectionCount: 0 };
    writeFileSync(file, JSON.stringify(raw), "utf-8");
    expect(store.get("cont-1")).toBeNull();
  });

  it("returns null for unknown ids and corrupt files", () => {
    const root = tmp();
    const store = new LocalContinuationStateStore(root);
    expect(store.get("ghost")).toBeNull();
    writeFileSync(path.join(root, "corrupt.json"), "{not json", "utf-8");
    expect(store.get("corrupt")).toBeNull();
  });

  it("deletes all state for a dispatch on terminal release", () => {
    const root = tmp();
    const store = new LocalContinuationStateStore(root);
    store.put(manifest());
    store.put(manifest({ continuationId: "cont-2" }));
    store.deleteDispatch("dispatch-1");
    expect(store.get("cont-1")).toBeNull();
    expect(store.get("cont-2")).toBeNull();
  });
});

describe("RecoveryStore", () => {
  it("publishes a continuation at a safe boundary and loads it for resume", () => {
    const store = new RecoveryStore(new LocalContinuationStateStore(tmp()));
    const published = store.publish({
      dispatchId: "dispatch-1",
      attemptOrdinal: 1,
      budgetCounters: { workerCalls: 1, totalTokens: 42, rejectionCount: 0 },
      completedCalls: [
        {
          callId: "call-a",
          workerName: "coder",
          purpose: "IMPLEMENT",
          status: "COMPLETED",
          startedSequence: 1,
          completedSequence: 2,
          workspaceRevisionBefore: 0,
          workspaceRevisionAfter: 1,
          tokenCount: 42,
        },
        {
          callId: "call-b",
          workerName: "coder",
          purpose: "IMPLEMENT",
          status: "FAILED",
          startedSequence: 3,
          completedSequence: 4,
          workspaceRevisionBefore: 1,
          workspaceRevisionAfter: 1,
          tokenCount: 0,
          errorCode: "WORKER_FAILED",
        },
      ],
      candidateTreeHash: "b".repeat(40),
      workspaceRevision: 1,
      verifierHistory: [],
    });
    const loaded = store.load(published.continuationId);
    expect(loaded).not.toBeNull();
    // Only COMPLETED call identities are restored (§17.5: replay
    // deduplicates worker calls by ID; failures are not effects).
    expect(loaded!.completedCallIds).toEqual(["call-a"]);
  });

  it("release deletes the dispatch's continuations", () => {
    const store = new RecoveryStore(new LocalContinuationStateStore(tmp()));
    const published = store.publish({
      dispatchId: "d1",
      attemptOrdinal: 1,
      budgetCounters: { workerCalls: 0, totalTokens: 0, rejectionCount: 0 },
      completedCalls: [],
      candidateTreeHash: "c".repeat(40),
      workspaceRevision: 0,
      verifierHistory: [],
    });
    store.release("d1");
    expect(store.load(published.continuationId)).toBeNull();
  });
});