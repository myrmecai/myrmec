// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * VerificationLedger contract tests (design §11 rules 1-11): append-only
 * records, lexicographic (attemptOrdinal, sequence) authority, exact-tree
 * binding, and the satisfies() completion answer.
 */
import { describe, it, expect } from "vitest";
import { InMemoryVerificationLedger } from "./VerificationLedger.js";

const TREE_A = "a".repeat(40);
const TREE_B = "b".repeat(40);

function verdict(over: Partial<Parameters<InMemoryVerificationLedger["record"]>[0]> = {}) {
  return {
    callId: "call-1",
    workerName: "verifier",
    verdict: "APPROVED" as const,
    summary: "ok",
    issues: [],
    workspaceRevision: 1,
    candidateTreeHash: TREE_A,
    attemptOrdinal: 1,
    ...over,
  };
}

describe("InMemoryVerificationLedger", () => {
  it("records append-only with a monotonic runner-owned sequence", () => {
    const ledger = new InMemoryVerificationLedger();
    const r1 = ledger.record(verdict());
    const r2 = ledger.record(verdict({ callId: "call-2" }));
    expect(r1.sequence).toBe(1);
    expect(r2.sequence).toBe(2);
    expect(ledger.records()).toHaveLength(2);
    expect(ledger.records()[0].sequence).toBeLessThan(ledger.records()[1].sequence);
  });

  it("satisfies only when every required worker APPROVED the exact tree", () => {
    const ledger = new InMemoryVerificationLedger();
    ledger.record(verdict({ workerName: "verifier-a", verdict: "APPROVED" }));
    // verifier-b has not approved.
    expect(ledger.satisfies(["verifier-a", "verifier-b"], TREE_A)).toBe(false);
    ledger.record(
      verdict({ callId: "c2", workerName: "verifier-b", verdict: "APPROVED" }),
    );
    expect(ledger.satisfies(["verifier-a", "verifier-b"], TREE_A)).toBe(true);
    // A different tree is a different answer (rule 5).
    expect(ledger.satisfies(["verifier-a", "verifier-b"], TREE_B)).toBe(false);
  });

  it("a REJECTED verdict for the same tree is not satisfied even with an earlier APPROVED (rule 8)", () => {
    const ledger = new InMemoryVerificationLedger();
    ledger.record(verdict({ verdict: "APPROVED", callId: "c1" }));
    // A higher-sequence REJECTED supersedes within the same attempt.
    ledger.record(verdict({ verdict: "REJECTED", callId: "c2" }));
    expect(ledger.satisfies(["verifier"], TREE_A)).toBe(false);
    // The audit history keeps both (rule 7).
    expect(ledger.records()).toHaveLength(2);
  });

  it("a REJECTED followed by APPROVED from a distinct invocation at the same revision satisfies", () => {
    const ledger = new InMemoryVerificationLedger();
    ledger.record(verdict({ verdict: "REJECTED", callId: "c1", issues: ["bad"] }));
    ledger.record(verdict({ verdict: "APPROVED", callId: "c2" }));
    expect(ledger.satisfies(["verifier"], TREE_A)).toBe(true);
    // The earlier rejection remains in records() (rule 7).
    expect(ledger.records().map((r) => r.verdict)).toEqual(["REJECTED", "APPROVED"]);
  });

  it("a new-attempt rejection supersedes an older higher-sequence approval (rule 8)", () => {
    const ledger = new InMemoryVerificationLedger();
    // Attempt 1 approves with a high dispatch-local sequence.
    ledger.record(verdict({ verdict: "APPROVED", attemptOrdinal: 1 }));
    ledger.record(verdict({ callId: "c2", verdict: "APPROVED", attemptOrdinal: 1 }));
    // A new attempt (ordinal 2) rejects with a LOWER sequence — the
    // lexicographically highest (attemptOrdinal, sequence) wins.
    ledger.record(verdict({ callId: "c3", verdict: "REJECTED", attemptOrdinal: 2 }));
    expect(ledger.satisfies(["verifier"], TREE_A)).toBe(false);
  });

  it("an approval is invalidated by a later tree change (rule 10)", () => {
    const ledger = new InMemoryVerificationLedger();
    ledger.record(verdict({ verdict: "APPROVED", candidateTreeHash: TREE_A }));
    expect(ledger.satisfies(["verifier"], TREE_B)).toBe(false);
  });

  it("an empty required list is trivially satisfied", () => {
    const ledger = new InMemoryVerificationLedger();
    expect(ledger.satisfies([], TREE_A)).toBe(true);
  });
});