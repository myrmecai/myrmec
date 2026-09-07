// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * BudgetController (design §13, §8.9): tracks worker calls, model usage,
 * and rejection count for one runner attempt. Both orchestrator and
 * worker `TurnExecutor` calls share the same controller, and it is
 * consulted INSIDE the model-tool loop — not only after a complete turn.
 *
 * Counting semantics (§13): `workerCalls` counts each ACCEPTED
 * `invoke_worker` execution — a delegation rejected by the budget is
 * never an execution. Once any counter exceeds its limit the breach is
 * FLAGGED: the run terminates using the configured budget action, so
 * every later check returns the same breach and the nested model loops
 * stop at their next boundary. Token usage is known only after a
 * response, so one response may overshoot the remaining budget; its
 * tool calls are not executed.
 */
import type { OrchestrationPolicyAuthoring } from "./types.js";

/** Where a budget check runs — each call site names itself for the error. */
export type BudgetCheckPoint =
  | "before-worker-call"
  | "before-model-call"
  | "before-tool-execution"
  | "after-rejection";

/** A budget breach with the specific stable error code (§14). */
export interface BudgetBreach {
  checkpoint: BudgetCheckPoint;
  errorCode: "WORKER_BUDGET_EXCEEDED" | "TOKEN_BUDGET_EXCEEDED" | "REJECTION_BUDGET_EXCEEDED";
  message: string;
}

/** Counters restorable by a same-dispatch restart (§13). */
export interface BudgetCounters {
  workerCalls: number;
  totalTokens: number;
  rejectionCount: number;
}

export interface BudgetController {
  /** §13: accept-or-reject one invoke_worker execution — counts ONLY
   * accepted executions; a rejected delegation does not count. */
  tryReserveWorkerCall(): BudgetBreach | null;
  /** §13: record every orchestrator/worker model response's tokens. */
  recordTokens(tokens: number): void;
  /** §13: record each REJECTED verdict immediately; flags the breach
   * when the rejection budget is exceeded. */
  recordRejection(): BudgetBreach | null;
  /** Flag a terminal breach: the run stops at the next boundary and
   * every later check returns the same breach (§13: the run terminates
   * using the configured budget action). */
  flagBreach(breach: BudgetBreach): void;
  /** Check at a call-site boundary. A flagged breach always wins. */
  check(checkpoint: BudgetCheckPoint): BudgetBreach | null;
  /** Cumulative observation for USAGE_UPDATED (§21). */
  counters(): BudgetCounters;
  /** The effective limits (already the min of assignment and any
   * tighten-only allowance overlay — engine mode only). */
  limits(): { maxWorkerCalls: number; maxTokens: number; maxVerifierRejectionsPerAttempt: number };
}

export class InMemoryBudgetController implements BudgetController {
  private readonly maxWorkerCalls: number;
  private readonly maxTokens: number;
  private readonly maxVerifierRejectionsPerAttempt: number;
  private workerCalls = 0;
  private totalTokens = 0;
  private rejections = 0;
  private flagged: BudgetBreach | null = null;

  constructor(
    budget: Pick<
      OrchestrationPolicyAuthoring["budget"],
      "maxWorkerCalls" | "maxTokens" | "maxVerifierRejectionsPerAttempt"
    >,
    counters?: BudgetCounters,
  ) {
    this.maxWorkerCalls = budget.maxWorkerCalls;
    this.maxTokens = budget.maxTokens;
    this.maxVerifierRejectionsPerAttempt = budget.maxVerifierRejectionsPerAttempt;
    if (counters) {
      this.workerCalls = counters.workerCalls;
      this.totalTokens = counters.totalTokens;
      this.rejections = counters.rejectionCount;
    }
  }

  tryReserveWorkerCall(): BudgetBreach | null {
    if (this.workerCalls + 1 > this.maxWorkerCalls) {
      const breach: BudgetBreach = {
        checkpoint: "before-worker-call",
        errorCode: "WORKER_BUDGET_EXCEEDED",
        message: `worker call limit exceeded: ${this.workerCalls} accepted of ${this.maxWorkerCalls}`,
      };
      this.flagBreach(breach);
      return breach;
    }
    this.workerCalls += 1;
    return null;
  }

  recordTokens(tokens: number): void {
    if (Number.isInteger(tokens) && tokens >= 0) {
      this.totalTokens += tokens;
    }
  }

  recordRejection(): BudgetBreach | null {
    this.rejections += 1;
    if (this.rejections > this.maxVerifierRejectionsPerAttempt) {
      const breach: BudgetBreach = {
        checkpoint: "after-rejection",
        errorCode: "REJECTION_BUDGET_EXCEEDED",
        message: `verifier rejection limit exceeded: ${this.rejections} > ${this.maxVerifierRejectionsPerAttempt}`,
      };
      this.flagBreach(breach);
      return breach;
    }
    return null;
  }

  flagBreach(breach: BudgetBreach): void {
    if (!this.flagged) {
      this.flagged = breach;
    }
  }

  check(checkpoint: BudgetCheckPoint): BudgetBreach | null {
    // A flagged breach is terminal: every later boundary reports the
    // same breach so nested model loops stop immediately (§13).
    if (this.flagged) return this.flagged;
    if (
      (checkpoint === "before-model-call" || checkpoint === "before-tool-execution") &&
      this.totalTokens > this.maxTokens
    ) {
      return {
        checkpoint,
        errorCode: "TOKEN_BUDGET_EXCEEDED",
        message: `token budget exceeded: ${this.totalTokens} > ${this.maxTokens}`,
      };
    }
    return null;
  }

  counters(): BudgetCounters {
    return {
      workerCalls: this.workerCalls,
      totalTokens: this.totalTokens,
      rejectionCount: this.rejections,
    };
  }

  limits() {
    return {
      maxWorkerCalls: this.maxWorkerCalls,
      maxTokens: this.maxTokens,
      maxVerifierRejectionsPerAttempt: this.maxVerifierRejectionsPerAttempt,
    };
  }
}