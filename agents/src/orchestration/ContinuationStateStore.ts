// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ContinuationStateStore (design §17.2, §13; plan Feature 7): persists
 * lightweight continuation state for one dispatch OUTSIDE the checkout
 * — under a Host-local root. V1 is the `HOST_LOCAL` subset: an encrypted
 * atomic-file provider is deferred with the recovery artifacts; this
 * store carries the non-source continuation manifest: counters, ledger,
 * candidate-tree state, and pending action references.
 *
 * Counter restoration scope (§13): restarting or replaying the same
 * `dispatchId` restores that dispatch's counters so a crash cannot reset
 * a budget; a new engine attempt has a new `dispatchId` and fresh
 * counters while restoring only completed-call identities, candidate
 * state, verifier history, and any pending action.
 */
import { createHash, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { BudgetCounters } from "./BudgetController.js";
import type { VerdictRecord } from "./VerificationLedger.js";
import type { WorkerCallResult } from "./types.js";

/** The persisted continuation manifest (§17.2's continuation.json). */
export interface ContinuationManifest {
  continuationId: string;
  dispatchId: string;
  attemptOrdinal: number;
  /** §13: same-dispatch restart restores these counters. */
  budgetCounters: BudgetCounters;
  /** Completed worker-call identities — replay deduplicates by these. */
  completedCallIds: string[];
  /** The candidate-tree state at the safe boundary. */
  candidateTreeHash: string;
  workspaceRevision: number;
  /** Verifier history ordered by (attemptOrdinal, sequence). */
  verifierHistory: VerdictRecord[];
  /** §17.4 HITL: the full pending governed action + the approval
   * request identity + expiry — the resume validation binds the typed
   * decision against exactly these. */
  pendingAction?: {
    actionId: string;
    type: string;
    riskClass: "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";
    summary: string;
    digest: string;
  };
  approvalRequestId?: string;
  suspensionExpiresAt?: string;
  /** Creation timestamp (ISO). */
  createdAt: string;
  /** SHA-256 over the canonical manifest content, set on write. */
  stateDigest: string;
}

export interface ContinuationStateStore {
  /** Atomically publish the current continuation for a dispatch. */
  put(manifest: Omit<ContinuationManifest, "stateDigest">): ContinuationManifest;
  /** Load by continuation id; validates the digest (fail closed). */
  get(continuationId: string): ContinuationManifest | null;
  /** Delete all state for a dispatch (terminal release, §16.5). */
  deleteDispatch(dispatchId: string): void;
}

/** Digest over the manifest's semantic content (no self-reference). */
function manifestDigest(m: Omit<ContinuationManifest, "stateDigest">): string {
  const canonical = JSON.stringify({
    continuationId: m.continuationId,
    dispatchId: m.dispatchId,
    attemptOrdinal: m.attemptOrdinal,
    budgetCounters: m.budgetCounters,
    completedCallIds: m.completedCallIds,
    candidateTreeHash: m.candidateTreeHash,
    workspaceRevision: m.workspaceRevision,
    verifierHistory: m.verifierHistory,
    ...(m.pendingAction ? { pendingAction: m.pendingAction } : {}),
    ...(m.approvalRequestId ? { approvalRequestId: m.approvalRequestId } : {}),
    ...(m.suspensionExpiresAt ? { suspensionExpiresAt: m.suspensionExpiresAt } : {}),
    createdAt: m.createdAt,
  });
  return createHash("sha256").update(canonical).digest("hex");
}

/**
 * The Host-local atomic-file implementation. Writes go to a temp file
 * then atomically rename over the target (§17.2 continuation
 * publication: temporary record, digest verification, atomic
 * current-pointer replacement).
 */
export class LocalContinuationStateStore implements ContinuationStateStore {
  private readonly root: string;

  constructor(root: string) {
    this.root = root;
    mkdirSync(root, { recursive: true });
  }

  private dispatchDir(dispatchId: string): string {
    return path.join(this.root, dispatchId);
  }

  private filePath(continuationId: string): string {
    return path.join(this.root, `${continuationId}.json`);
  }

  put(manifest: Omit<ContinuationManifest, "stateDigest">): ContinuationManifest {
    const full: ContinuationManifest = { ...manifest, stateDigest: manifestDigest(manifest) };
    mkdirSync(this.dispatchDir(manifest.dispatchId), { recursive: true });
    const target = this.filePath(manifest.continuationId);
    const tmp = `${target}.tmp-${randomUUID()}`;
    writeFileSync(tmp, JSON.stringify(full), "utf-8");
    renameSync(tmp, target);
    return full;
  }

  get(continuationId: string): ContinuationManifest | null {
    const file = this.filePath(continuationId);
    if (!existsSync(file)) return null;
    let parsed: ContinuationManifest;
    try {
      parsed = JSON.parse(readFileSync(file, "utf-8")) as ContinuationManifest;
    } catch {
      // Corrupt state fails closed (§17.2 RECOVERY_SNAPSHOT_INVALID
      // semantics for the local subset).
      return null;
    }
    const { stateDigest: recorded, ...rest } = parsed;
    if (manifestDigest(rest) !== recorded) {
      // Digest mismatch: never partially restore.
      return null;
    }
    return parsed;
  }

  deleteDispatch(dispatchId: string): void {
    // Continuations live as flat <id>.json files; each manifest carries
    // its dispatchId, so release removes exactly that dispatch's state.
    const dir = this.dispatchDir(dispatchId);
    if (existsSync(dir)) {
      rmSync(dir, { recursive: true, force: true });
    }
    if (existsSync(this.root)) {
      for (const entry of readdirSync(this.root)) {
        if (!entry.endsWith(".json")) continue;
        const file = path.join(this.root, entry);
        try {
          const parsed = JSON.parse(readFileSync(file, "utf-8")) as ContinuationManifest;
          if (parsed.dispatchId === dispatchId) {
            rmSync(file, { force: true });
          }
        } catch {
          // Unreadable entries are not this dispatch's state.
        }
      }
    }
  }
}

/**
 * RecoveryStore (§8.12, plan Feature 7 lightweight subset): persists a
 * continuation record after every completed worker call (safe model
 * boundary) and hands the manifest to a new attempt. The source-bearing
 * artifact remains optional until Feature 10 — a continuation whose
 * candidate tree matches the reconstructed checkout needs no artifact.
 */
export class RecoveryStore {
  constructor(private readonly store: ContinuationStateStore) {}

  /**
   * Publish the current continuation after a safe boundary. Returns the
   * durable manifest.
   */
  publish(input: {
    dispatchId: string;
    attemptOrdinal: number;
    budgetCounters: BudgetCounters;
    completedCalls: WorkerCallResult[];
    candidateTreeHash: string;
    workspaceRevision: number;
    verifierHistory: VerdictRecord[];
  }): ContinuationManifest {
    const continuationId = `cont-${input.dispatchId}-${Date.now().toString(36)}`;
    return this.store.put({
      continuationId,
      dispatchId: input.dispatchId,
      attemptOrdinal: input.attemptOrdinal,
      budgetCounters: input.budgetCounters,
      completedCallIds: input.completedCalls
        .filter((c) => c.status === "COMPLETED")
        .map((c) => c.callId),
      candidateTreeHash: input.candidateTreeHash,
      workspaceRevision: input.workspaceRevision,
      verifierHistory: input.verifierHistory,
      createdAt: new Date().toISOString(),
    });
  }

  /** Load a continuation for resume; null fails closed. */
  load(continuationId: string): ContinuationManifest | null {
    return this.store.get(continuationId);
  }

  /** Terminal release deletes all dispatch state (§16.5). */
  release(dispatchId: string): void {
    this.store.deleteDispatch(dispatchId);
  }
}