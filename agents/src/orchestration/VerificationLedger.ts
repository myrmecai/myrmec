// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * VerificationLedger (design §8.8, rules §11): stores immutable verdict
 * records and answers whether every required worker has an authoritative
 * APPROVED verdict for the exact current candidate tree.
 *
 * The ledger is append-only (rule 7): it never overwrites rejections or
 * older approvals. For one worker+tree the lexicographically highest
 * runner-owned `(attemptOrdinal, sequence)` verdict is authoritative
 * (rule 8), so a verdict from a new attempt supersedes every restored
 * verdict from an older attempt.
 */
import type { VerifierResult } from "./types.js";

export interface VerdictRecord {
  callId: string;
  workerName: string;
  verdict: "APPROVED" | "REJECTED";
  summary: string;
  issues: string[];
  workspaceRevision: number;
  candidateTreeHash: string;
  attemptOrdinal: number;
  sequence: number;
}

export interface VerificationLedger {
  record(input: Omit<VerdictRecord, "sequence">): VerdictRecord;
  records(): readonly VerdictRecord[];
  /**
   * True when every named worker's authoritative verdict for exactly
   * `candidateTreeHash` is APPROVED (design §11 rule 11).
   */
  satisfies(requiredWorkers: string[], candidateTreeHash: string): boolean;
}

/** In-memory ledger for one runner attempt (design §8.8). */
export class InMemoryVerificationLedger implements VerificationLedger {
  private readonly store: VerdictRecord[] = [];
  private nextSequence = 0;

  record(input: Omit<VerdictRecord, "sequence">): VerdictRecord {
    const record: VerdictRecord = { ...input, sequence: ++this.nextSequence };
    this.store.push(record);
    return record;
  }

  records(): readonly VerdictRecord[] {
    return this.store;
  }

  satisfies(requiredWorkers: string[], candidateTreeHash: string): boolean {
    for (const workerName of requiredWorkers) {
      const authoritative = this.authoritative(workerName, candidateTreeHash);
      if (!authoritative || authoritative.verdict !== "APPROVED") {
        return false;
      }
    }
    return true;
  }

  /**
   * The lexicographically highest `(attemptOrdinal, sequence)` record for
   * this worker at exactly this tree (design §11 rule 8).
   */
  private authoritative(
    workerName: string,
    candidateTreeHash: string,
  ): VerdictRecord | undefined {
    let best: VerdictRecord | undefined;
    for (const r of this.store) {
      if (r.workerName !== workerName || r.candidateTreeHash !== candidateTreeHash) {
        continue;
      }
      if (
        !best ||
        r.attemptOrdinal > best.attemptOrdinal ||
        (r.attemptOrdinal === best.attemptOrdinal && r.sequence > best.sequence)
      ) {
        best = r;
      }
    }
    return best;
  }
}

/** Convert a ledger record into the §7.3 `VerifierResult` wire shape. */
export function toVerifierResult(record: VerdictRecord): VerifierResult {
  return {
    callId: record.callId,
    workerName: record.workerName,
    verdict: record.verdict,
    summary: record.summary,
    issues: [...record.issues],
    workspaceRevision: record.workspaceRevision,
    candidateTreeHash: record.candidateTreeHash,
    attemptOrdinal: record.attemptOrdinal,
    sequence: record.sequence,
  };
}