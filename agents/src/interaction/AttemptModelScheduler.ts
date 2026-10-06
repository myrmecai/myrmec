// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AttemptModelScheduler (design 14.5): the attempt-level model-call
 * scheduler. V1 serializes INDIVIDUAL model invocations across the
 * orchestrator/helper loop and the interaction loop with ONE permit: do
 * not hold it during a whole helper/model-tool loop or while parked in
 * the hold gate. The ONE admitted interaction gets priority at the next
 * available model boundary: an INTERACTION acquisition jumps ahead of
 * earlier-queued ORCHESTRATION calls.
 *
 * Permit acquisition is NOT an active leaf. Every ORCHESTRATION
 * acquisition rechecks the hold gate AFTER taking the permit: when the
 * gate is held, the permit is RELEASED again before parking at
 * {@link AttemptSchedulerGate.enterOperation} - otherwise a queued model
 * call would pin the permit while HELD and starve the admitted
 * interaction indefinitely (14.5 "give one admitted interaction priority
 * at the next available model boundary"; 22.6 "the response deadline
 * includes scheduler wait"). On wake the acquisition retries (fresh
 * gate + budget check by the caller's loop); stop()/deadline aborts
 * parked waiters with {@link HoldAbortedError} (14.3: never leave parked
 * promises hanging) and rejects every queued waiter.
 *
 * INTERACTION work does not consult the orchestrator's hold gate (22.5:
 * "Do not gate the interactive controller with the orchestrator's hold
 * gate"); its budget recheck rides the caller (Task 7's turn loop).
 */

import {
  HoldAbortedError,
  type SafePoint,
} from "./ExecutionControlCoordinator.js";

/** The hold-gate surface the scheduler rechecks (satisfied by the
 * coordinator; tests inject a stub). Budget/policy rechecks beyond the
 * gate stay the caller's loop responsibility (14.5). */
export interface AttemptSchedulerGate {
  snapshot(): Readonly<{ effectiveState: "RUNNING" | "HOLD_REQUESTED" | "HELD" }>;
  enterOperation(point: SafePoint): Promise<{ release(): void }>;
}

/** Which internal model loop an invocation belongs to. */
export type AttemptModelSource = "ORCHESTRATION" | "INTERACTION";

export interface AttemptModelSchedulerOptions {
  /** The hold gate ORCHESTRATION acquisitions recheck. Absent (plain
   * unit use): no gate recheck - one permit, priority, finally-release. */
  control?: AttemptSchedulerGate;
}

interface Waiter {
  source: AttemptModelSource;
  /** Called by the pump when THIS waiter takes the permit (no value). */
  resolve(): void;
  reject(error: unknown): void;
}

/**
 * One permit per attempt. FIFO within a source class, but a pending
 * INTERACTION waiter is picked before any queued ORCHESTRATION waiter
 * when the permit frees (14.5 priority rule). The permit is always
 * released in the scheduler's own finally - a throwing work never leaks
 * it.
 */
export class AttemptModelScheduler {
  private readonly control?: AttemptSchedulerGate;
  private busy = false;
  /** Interaction waiters (at most one in V1) jump the FIFO queue. */
  private readonly interactionWaiters: Waiter[] = [];
  private readonly orchestrationWaiters: Waiter[] = [];
  private stopped = false;

  constructor(options: AttemptModelSchedulerOptions = {}) {
    this.control = options.control;
  }

  /**
   * Admit ONE model invocation. Order of operations:
   *   1. queue for the permit (INTERACTION jump ahead),
   *   2. for ORCHESTRATION ONLY: take the permit, then RELEASE it and
   *      park at the hold gate while held (retry after wake) - the
   *      permit is never held while parked; on CONTINUE the same call
   *      re-queues and the gate/budget are rechecked at admission,
   *   3. run work; release in finally.
   * stop() rejects queued/parked waiters with HoldAbortedError.
   *
   * Wake contract: when CONTINUE resolves a parked ORCHESTRATION waiter
   * the gate hands it a REAL lease (22.5: the woken waiter counts as an
   * active leaf from its wake onward). The scheduler RELEASES that lease
   * immediately - the wake is admission evidence, not leaf work - then
   * re-queues and rechecks the gate fresh at the next acquisition, so
   * the leaf count never leaks a parked waiter across hold/continue
   * cycles.
   */
  async invoke<T>(
    source: AttemptModelSource,
    work: () => Promise<T>,
  ): Promise<T> {
    for (;;) {
      if (this.stopped) {
        throw new HoldAbortedError("STOP", "model scheduler stopped");
      }
      // 1+2: permit + gate. acquireOrdering returns null on stop/abort.
      const acquired = await this.acquirePermitAndGate(source);
      if (acquired === null) {
        // The scheduler was stopped while waiting - a queued call never
        // runs after stop (14.3).
        throw new HoldAbortedError("STOP", "model scheduler stopped");
      }
      try {
        return await work();
      } finally {
        this.releasePermit();
      }
    }
  }

  /**
   * Terminal teardown: reject every queued/parked waiter. In-flight work
   * completes (it already ran through the caller's own cancellation
   * checks) and frees its permit.
   */
  stop(): void {
    this.stopped = true;
    const waiters = [...this.interactionWaiters, ...this.orchestrationWaiters];
    this.interactionWaiters.length = 0;
    this.orchestrationWaiters.length = 0;
    for (const waiter of waiters) {
      waiter.reject(new HoldAbortedError("STOP", "model scheduler stopped"));
    }
  }

  // ---- internals -----------------------------------------------------

  private async acquirePermitAndGate(
    source: AttemptModelSource,
  ): Promise<null | undefined> {
    for (;;) {
      if (this.stopped) return null;

      // Permit acquisition is NOT an active leaf (14.5).
      const gotPermit = await this.awaitPermit(source);
      if (!gotPermit) return null; // stopped while queued

      if (source === "INTERACTION") {
        // The interaction loop is not gated by the orchestrator's hold
        // (22.5); hold the permit and run.
        return undefined;
      }

      // ORCHESTRATION: recheck the gate WHILE HOLDING the permit. When
      // held, RELEASE first and park - a queued model call must never
      // pin the permit while HELD (14.5 priority rule), otherwise the
      // admitted interaction could never take its boundary.
      const snapshot = this.control?.snapshot();
      if (!this.control || snapshot?.effectiveState === "RUNNING") {
        return undefined;
      }
      this.releasePermitUnsafe();
      const point: SafePoint = "BEFORE_MODEL_CALL";
      // stop/deadline aborts the parked gate waiter with the gate's typed
      // error (14.3) — rethrown as-is; the queued call is never retried.
      const lease = await this.control.enterOperation(point);
      if (this.stopped) {
        // The gate handed a wakeup lease but the scheduler was stopped
        // while waking - give the lease back before aborting (the
        // coordinator counts the woken waiter as an active leaf from
        // its wake onward).
        lease.release();
        throw new HoldAbortedError("STOP", "model scheduler stopped");
      }
      // CONTINUE woke this parked waiter with a REAL lease: the real
      // coordinator already incremented its active-leaf count and the
      // woken caller MUST release it - the re-queued admission below is
      // evidence, not a leaf (22.5: a parked continuation is not active
      // work). Releasing immediately keeps the leaf count at zero so a
      // drain can still publish HELD on the next HOLD.
      lease.release();
      // Loop restart: the permit is re-acquired and the gate is
      // rechecked fresh at admission (a re-HOLD between the wake and
      // the re-check parks again rather than running).
      continue;
    }
  }

  /** Queue for the permit; resolves when THIS waiter holds it. */
  private awaitPermit(source: AttemptModelSource): Promise<boolean> {
    if (!this.busy) {
      this.busy = true;
      return Promise.resolve(true);
    }
    return new Promise<boolean>((resolve, reject) => {
      // Wrap: pumpIfFree calls waiter.resolve() WITHOUT a value - the
      // raw resolve would settle this promise to `undefined` (a falsey
      // gotPermit the invoke loop would mistake for a stop-abort).
      const waiter: Waiter = { source, resolve: () => resolve(true), reject };
      if (source === "INTERACTION") {
        this.interactionWaiters.push(waiter);
      } else {
        this.orchestrationWaiters.push(waiter);
      }
      this.pumpIfFree();
    });
  }

  /** Release the permit and wake the next waiter (INTERACTION first). */
  private releasePermit(): void {
    this.releasePermitUnsafe();
  }

  private releasePermitUnsafe(): void {
    this.busy = false;
    this.pumpIfFree();
  }

  private pumpIfFree(): void {
    if (this.busy) return;
    // INTERACTION priority: the pending (or queued) interactive
    // invocation takes the permit before any queued ORCHESTRATION work.
    const next =
      this.interactionWaiters.shift() ?? this.orchestrationWaiters.shift();
    if (!next) return;
    this.busy = true;
    next.resolve();
  }
}