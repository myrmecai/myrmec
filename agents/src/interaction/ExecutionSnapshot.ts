// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ExecutionSnapshot (design 14.2/§22.4 identities): the immutable,
 * bounded metadata snapshot an orchestration execution publishes through
 * the EXISTING PROGRESS / USAGE_UPDATED execution events. Built by the
 * runner/executor from ids, progress state, and the shared budget - and
 * NOTHING else: no prompts, no tool results, no file contents, no
 * credentials, no mutable workspace handles (14.6: the interaction
 * surfaces immutable snapshots only; capture policy still governs).
 *
 * Consumers (Task 7's read-only interaction tools and clients) treat the
 * snapshot as STALE AT VIEW TIME: carry version + capturedAt, never
 * claim a live lock or a final commit from it.
 */

/** §22.4's identities: one execution's view of its attempt. */
export interface ExecutionSnapshot {
  /** Attempt/execution identity (22.4 - engine-issued UUIDs). */
  executionId: string;
  dispatchId: string;
  workflowId: string;
  runId: string;
  stepId: string;
  taskId: string;
  attemptId: string;
  attemptOrdinal: number;
  /** The hold overlay observation (22.4 HoldState). */
  holdState: "RUNNING" | "HOLD_REQUESTED" | "HELD";
  /** Monotonic progress identity: bumped per published snapshot. */
  progressVersion: number;
  /** UTC instant this snapshot was captured. */
  progressCapturedAt: string;
  /** Completed orchestrator function calls (helper delegations). */
  helperCallsCompleted: number;
  /** Recorded verifier verdicts so far. */
  verifierRejections: number;
  /** Budget: effective limits (assignment min tighten-only overlay). */
  budgetLimits: {
    maxWorkerCalls: number;
    maxTokens: number;
    maxVerifierRejectionsPerAttempt: number;
  };
  /** Budget: cumulative accounted subtotals (the shared controller's). */
  budgetTotal: {
    helperCalls: number;
    totalTokens: number;
    rejectionCount: number;
  };
  /** §22.6 usage attribution: KNOWN totals above, UNKNOWN = not zero. */
  usageStatus: "KNOWN" | "UNKNOWN";
}

export interface ExecutionSnapshotIds {
  executionId: string;
  dispatchId: string;
  workflowId: string;
  runId: string;
  stepId: string;
  taskId: string;
  attemptId: string;
  attemptOrdinal: number;
  holdState: "RUNNING" | "HOLD_REQUESTED" | "HELD";
}

export interface ExecutionSnapshotBudget {
  budgetLimits: ExecutionSnapshot["budgetLimits"];
  budgetTotal: ExecutionSnapshot["budgetTotal"];
  usageStatus: ExecutionSnapshot["usageStatus"];
}

/** Frozen build from immutable inputs; no references escape. */
export function freezeExecutionSnapshot(
  ids: ExecutionSnapshotIds,
  budget: ExecutionSnapshotBudget,
  helperCallsCompleted: number,
  verifierRejections: number,
  progressVersion: number,
  progressCapturedAt: string,
): Readonly<ExecutionSnapshot> {
  return Object.freeze({
    ...ids,
    helperCallsCompleted,
    verifierRejections,
    ...budget,
    progressVersion,
    progressCapturedAt,
  });
}

/**
 * The snapshot publisher (14.2 "Read-only snapshot type and publisher").
 * Emits through the EXISTING orchestration event sink - PROGRESS carries
 * the identity/progress block, USAGE_UPDATED the budget block - keeping
 * each event's data JSON-bounded by the sink's own maxBytes budget.
 * Publishing neverthrows: a sink failure is the sink's own OutboxUnhealthyError,
 * which the caller's run loop already respects.
 */
export class ExecutionSnapshotPublisher {
  private readonly emit: (event: {
    dispatch: {
      workflowId: string;
      runId: string;
      stepId: string;
      taskId: string;
      attemptId: string;
      attemptOrdinal: number;
      dispatchId: string;
    };
    type: string;
    [key: string]: unknown;
  }) => Promise<unknown>;
  private readonly ids: () => ExecutionSnapshotIds;
  private readonly budgetOf: () => ExecutionSnapshotBudget;
  private readonly helperCallsOf: () => number;
  private readonly rejectionsOf: () => number;
  private version = 0;

  constructor(options: {
    emit: (event: {
      dispatch: {
        workflowId: string;
        runId: string;
        stepId: string;
        taskId: string;
        attemptId: string;
        attemptOrdinal: number;
        dispatchId: string;
      };
      type: string;
      [key: string]: unknown;
    }) => Promise<unknown>;
    ids: () => ExecutionSnapshotIds;
    budgetOf: () => ExecutionSnapshotBudget;
    helperCallsOf: () => number;
    rejectionsOf: () => number;
  }) {
    this.emit = options.emit;
    this.ids = options.ids;
    this.budgetOf = options.budgetOf;
    this.helperCallsOf = options.helperCallsOf;
    this.rejectionsOf = options.rejectionsOf;
  }

  /** The CURRENT immutable snapshot (read-only tools, Task 7). */
  current(): Readonly<ExecutionSnapshot> {
    return freezeExecutionSnapshot(
      this.ids(),
      this.budgetOf(),
      this.helperCallsOf(),
      this.rejectionsOf(),
      this.version,
      new Date().toISOString(),
    );
  }

  /** Publish PROGRESS + USAGE_UPDATED with the fresh snapshot. Bounded:
   * only the §22.4 identity/progress/budget fields ride; the sink's
   * CaptureFilter/maxBytes strips or truncates anything else. */
  async publish(): Promise<void> {
    this.version += 1;
    const snapshot = this.current();
    const dispatch = {
      workflowId: snapshot.workflowId,
      runId: snapshot.runId,
      stepId: snapshot.stepId,
      taskId: snapshot.taskId,
      attemptId: snapshot.attemptId,
      attemptOrdinal: snapshot.attemptOrdinal,
      dispatchId: snapshot.dispatchId,
    };
    const data = snapshotToData(snapshot);
    await this.emit({
      dispatch,
      type: "PROGRESS",
      status: snapshot.holdState,
      progressMessage: `helperCalls ${snapshot.helperCallsCompleted}, tokens ${snapshot.budgetTotal.totalTokens}`,
      percentage: 0,
      ...data,
    });
    await this.emit({
      dispatch,
      type: "USAGE_UPDATED",
      usage: snapshot.budgetTotal,
      ...data,
    });
  }
}

/** The bounded data block both events share (identity + progress + budget). */
function snapshotToData(snapshot: Readonly<ExecutionSnapshot>): Record<string, unknown> {
  return {
    executionId: snapshot.executionId,
    dispatchId: snapshot.dispatchId,
    workflowId: snapshot.workflowId,
    runId: snapshot.runId,
    stepId: snapshot.stepId,
    taskId: snapshot.taskId,
    attemptId: snapshot.attemptId,
    attemptOrdinal: snapshot.attemptOrdinal,
    holdState: snapshot.holdState,
    progressVersion: snapshot.progressVersion,
    progressCapturedAt: snapshot.progressCapturedAt,
    helperCallsCompleted: snapshot.helperCallsCompleted,
    verifierRejections: snapshot.verifierRejections,
    budgetLimits: snapshot.budgetLimits,
    budgetTotal: snapshot.budgetTotal,
    usageStatus: snapshot.usageStatus,
  };
}