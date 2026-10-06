// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ExecutionControlCoordinator (design 14.2-14.4, protocol 22.4/22.5): the
 * ONE serialized state owner per execution - controls, leaf-operation
 * admission, safe-point hold, interaction slot, idle timer, confirmation
 * inhibitor, cancellation. A pure state machine: no imports from worker/
 * orchestration/executor modules; the clock, timer scheduler, durable
 * seam and state emitter are all injected.
 *
 * Serialization contract (14.3): every mutation runs as one synchronous
 * closure under the coordinator's internal serialization flag; the
 * coordinator NEVER holds that flag across an await of a model, helper,
 * transport ack, safe point, or user confirmation. The only awaits live
 * in `apply` (outside the turn) and in the durability record
 * observations - both are the coordinator's own seam writes, never
 * leased/model/ack paths.
 *
 * Durability before liveness (22.4 "persist before send/apply/ack"):
 * every published state is persisted into the session's outbox sequence
 * BEFORE the in-memory publish, chained so records land in exactly
 * stateSequence order. A command's disposition (its 22.4 replay marker)
 * is stamped ONLY after the command's durability records resolved: a
 * fully durable command turn resolves `apply` (an engine ack is then
 * proof of persistence), while ANY record failure of the command's turn
 * REJECTS `apply`, leaves the disposition unstamped, and lets the
 * engine's re-drive of the identical message take the FULL apply path
 * again (fresh records at a fresh stateSequence - never an "already
 * applied" replay over records that never landed). One record's failure
 * never denies a LATER record its own persist attempt: per-record
 * verdicts ride the chain as VALUES, never as chain poison. Concurrent
 * duplicates/re-drives of the same messageId while a durability is open
 * join the first attempt's outcome: no double mutation, no double
 * emission, no double timer.
 *
 * The idle timer is generation-fenced (14.4): every cancel/rearm
 * invalidates earlier callbacks by generation counter, and a callback
 * rechecks ALL conditions inside the serialization turn via
 * {@link mayAutoResume} before publishing RUNNING/HOLD_IDLE_EXPIRED.
 * Automatic transitions retain the accepted engine revision and advance
 * stateSequence only - the SDK never fabricates an engine revision.
 */

import type {
  ExecutionControlPayload,
  ExecutionControlRequestResolvedPayload,
  ExecutionControlStatePayload,
  InteractionPolicy,
} from "../protocol/unifiedFrames.js";
import type { Logger } from "../models/index.js";

/** Where a cooperative hold can take effect (14.2/22.4 safe points). */
export type SafePoint =
  | "BEFORE_MODEL_CALL"
  | "BEFORE_TOOL_EXECUTION"
  | "BEFORE_HELPER_CALL"
  | "BEFORE_GIT_EFFECT";

export type HoldState = "RUNNING" | "HOLD_REQUESTED" | "HELD";

/** Leases count ORCHESTRATION LEAF WORK ONLY - never interactive turns,
 * never a nested helper parent waiting on its child (14.2/22.5). */
export interface OperationLease {
  release(): void;
}

/** 14.2 contract, verbatim. */
export interface ExecutionControl {
  apply(command: ExecutionControlPayload, commandMessageId: string): Promise<void>;
  enterOperation(point: SafePoint): Promise<OperationLease>;
  beginInteraction(interactionId: string): boolean;
  endInteraction(interactionId: string): void;
  resolveProposal(payload: ExecutionControlRequestResolvedPayload): void;
  setConnectionReady(ready: boolean): void;
  stop(reason: "CANCEL" | "TERMINAL" | "CHANNEL_LOST"): void;
  snapshot(): Readonly<ControlSnapshot>;
}

/**
 * Protocol-defined fields (22.4) plus the INTERNAL fence fields
 * (connectionReady, stopped, deadlineExpired): only protocol-defined
 * fields ever cross the wire - the wire payload builder copies the 22.4
 * fields only. Snapshots are immutable frozen copies with a monotonic
 * snapshot version and capturedAt (14.2).
 */
export interface ControlSnapshot {
  effectiveState: HoldState;
  /** Highest accepted engine revision; 0 before any command. */
  acceptedControlRevision: number;
  /** Monotonic host sequence stamped on EVERY published state. */
  stateSequence: number;
  /** UTC ISO instant or null; armed only while HELD without inhibitors. */
  idleResumeAt: string | null;
  /** The one admitted interaction, or null (no queue in V1). */
  pendingInteractionId: string | null;
  /** Owned confirmation inhibitors (22.7). */
  pendingControlRequestIds: ReadonlyArray<string>;
  // ---- internal fence fields (never cross the wire) ----
  connectionReady: boolean;
  stopped: boolean;
  deadlineExpired: boolean;
  /** Internal monotonic snapshot version for stale-copy rejection. */
  version: number;
  /** UTC timestamp for protocol observations (14.4). */
  capturedAt: string;
  /** The safe point of the leaf whose release produced HELD (evidence). */
  safePoint: SafePoint | null;
  /** Last published reasonCode, for wire-rebuild fallbacks. */
  lastReasonCode: string;
}

/** Rejection catalogue for refused engine commands (22.4). */
export const CONTROL_ERROR_CODE = {
  STALE_CONTROL_REVISION: "STALE_CONTROL_REVISION",
  CONTROL_REVISION_CONFLICT: "CONTROL_REVISION_CONFLICT",
  INVALID_MESSAGE: "INVALID_MESSAGE",
  EXECUTION_TERMINAL: "EXECUTION_TERMINAL",
} as const;
export type ControlErrorCode =
  (typeof CONTROL_ERROR_CODE)[keyof typeof CONTROL_ERROR_CODE];

/** The refusal facts a REJECTED state carries (22.4). */
export interface ControlRejection {
  reasonCode: string;
  errorCode: ControlErrorCode;
  rejectedControlRevision: number;
}

/** Why a gate woke (or aborted) its parked waiters. */
export type HoldExitReason =
  | "CONTINUE"
  | "STOP"
  | "CHANNEL_LOST"
  | "DEADLINE_EXPIRED";

/**
 * Thrown to parked {@link enterOperation} waiters when the gate wakes for
 * stop/deadline/cancel/terminal (14.3: never leave parked promises
 * hanging). The executor's cancellation/terminal path takes over.
 */
export class HoldAbortedError extends Error {
  constructor(
    public readonly reason: HoldExitReason,
    message?: string,
  ) {
    super(message ?? `hold gate aborted: ${reason}`);
    this.name = "HoldAbortedError";
  }
}

// ---- Injected seams (fakes in tests; no real sleeps anywhere) ----

/** Monotonic clock for live timer calculations (14.4). */
export interface MonotonicClock {
  now(): number;
}

/** One-shot timer scheduler. Callbacks fire, but the coordinator fences
 * stale ones by generation; scheduling itself never touches state. */
export interface TimerScheduler {
  schedule(delayMs: number, callback: () => void): void;
}

/**
 * The durable seam (14.2 ownership table): every published control state
 * is persisted into the session's outbox sequence BEFORE the in-memory
 * publish. The concrete mapping onto the shared `OrchestrationOutbox`
 * record family is the `control_state` record kind in
 * `protocol/OrchestrationOutbox.ts`; tests inject stubs. The seam
 * assigns a UNIQUE frame messageId per RECORD - ONE command can emit
 * several control_state records and each needs its own wire identity
 * for the ack contract (protocol 12.1). `commandMessageId` - the
 * originating command's messageId - is the correlation the sender
 * bridge stamps into the frame envelope (22.4); timer-driven states
 * carry none (null).
 */
export interface ControlStateOutbox {
  persistControlState(
    payload: ExecutionControlStatePayload,
    commandMessageId: string | null,
  ): Promise<void>;
}

/** The live wire emitter (bridge to the session sender). */
export interface ControlStateEmitter {
  publish(payload: ExecutionControlStatePayload): void;
}

export interface ExecutionControlCoordinatorOptions {
  executionId: string;
  dispatchId: string;
  /** The effective immutable session policy block (22.2). */
  policy: InteractionPolicy;
  clock: MonotonicClock;
  scheduler: TimerScheduler;
  outbox: ControlStateOutbox;
  emitter: ControlStateEmitter;
  logger?: Logger;
}

const HOLD_EXIT_BY_STOP_REASON = {
  CANCEL: "STOP",
  TERMINAL: "STOP",
  CHANNEL_LOST: "CHANNEL_LOST",
} as const satisfies Record<"CANCEL" | "TERMINAL" | "CHANNEL_LOST", HoldExitReason>;

/** Wire reason codes for idle-clock-driven state notifications (22.4). */
const IDLE_REASON = {
  INTERACTION_ACTIVITY: "INTERACTION_ACTIVITY",
  INTERACTION_SETTLED: "INTERACTION_SETTLED",
  CONFIRMATION_PENDING: "CONFIRMATION_PENDING",
  CONFIRMATION_SETTLED: "CONFIRMATION_SETTLED",
  HOLD_IDLE_EXPIRED: "HOLD_IDLE_EXPIRED",
} as const;

interface ParkedWaiter {
  point: SafePoint;
  resolve(lease: OperationLease): void;
  reject(error: HoldAbortedError): void;
}

/**
 * One command's in-flight durability (messageId-keyed). Concurrent
 * duplicates of the same messageId share `outcome` - it settles when the
 * leader's durability settles, so a re-driven command never mutates
 * twice.
 */
interface InFlight {
  fingerprint: string;
  /** Resolves (disposition stamped) / rejects (persistence failed). */
  outcome: Promise<void>;
  resolve(): void;
  reject(error: Error): void;
}

/**
 * The durability verdict of ONE chained record. Failures are carried as
 * chain VALUES, never swallowed and never chain poison: the record's
 * own observers read the verdict (a command turn rejects on its first
 * record failure), and a failing record never denies the NEXT record
 * its own persist attempt.
 */
type ChainRecordResult = { ok: true } | { ok: false; error: unknown };

/** What one serialized apply turn decided for a command. */
type ApplyTurnOutcome =
  | {
      /** Stored disposition already durable: replay, no reapply. */
      kind: "replay";
    }
  | {
      /** Fresh attempt or joined in-flight duplicate: await the outcome. */
      kind: "await";
      outcome: Promise<void>;
    };

/** The settle error for a failed record (Error identity preserved). */
function controlPersistError(error: unknown): Error {
  if (error instanceof Error) return error;
  return new Error(
    `control-state persistence failed: ${
      typeof error === "string" ? error : JSON.stringify(error)
    }`,
  );
}

/**
 * The core invariant, verbatim from the plan: every idle timer callback
 * rechecks these conditions inside the serialization turn before
 * publishing RUNNING/HOLD_IDLE_EXPIRED.
 */
export function mayAutoResume(state: ControlSnapshot, now: number): boolean {
  return state.effectiveState === "HELD" && state.connectionReady &&
    state.pendingInteractionId === null && state.pendingControlRequestIds.length === 0 &&
    state.idleResumeAt !== null && now >= Date.parse(state.idleResumeAt) &&
    !state.stopped && !state.deadlineExpired;
}

const noopLogger: Logger = { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} };

/**
 * One serialized state owner per execution. See the module doc for the
 * locking, durability, and fencing rules; 14.3/22.5 for the transitions.
 */
export class ExecutionControlCoordinator implements ExecutionControl {
  private readonly executionId: string;
  private readonly dispatchId: string;
  private readonly policy: InteractionPolicy;
  private readonly clock: MonotonicClock;
  private readonly scheduler: TimerScheduler;
  private readonly outbox: ControlStateOutbox;
  private readonly emitter: ControlStateEmitter;
  private readonly log: Logger;

  // ---- state (touched only inside a serialization turn) ----
  private effectiveState: HoldState = "RUNNING";
  private acceptedControlRevision = 0;
  private acceptedCommand: {
    revision: number;
    fingerprint: string;
    reasonCode: string;
    commandMessageId: string;
  } | null = null;
  private stateSequence = 0;
  private snapshotVersion = 0;
  /** The armed idle resume instant; survives disconnect, not inhibitors. */
  private idleResumeAt: string | null = null;
  private activeLeaves = 0;
  private heldAtSafePoint: SafePoint | null = null;
  private lastLeaseSafePoint: SafePoint | null = null;
  private pendingInteractionId: string | null = null;
  private readonly pendingControlRequestIds = new Set<string>();
  private connectionReady = true;
  private stopped: HoldExitReason | null = null;
  private deadlineExpired = false;
  private readonly parkers: ParkedWaiter[] = [];
  /**
   * Replay-visible dispositions (messageId -> command fingerprint).
   * Stamped ONLY AFTER the command's durability records resolved (22.4:
   * persist before ack) - a disposition here implies the command's
   * records are durably recorded, so a failed persist never leaves an
   * "already applied" mark and the engine's re-drive of the identical
   * command takes the FULL apply path again.
   */
  private readonly dispositions = new Map<string, string>();
  /**
   * Durability still in flight per messageId. A concurrent re-drive or
   * duplicate of the same messageId joins the FIRST attempt's outcome -
   * never a double mutation, never a double emission, never a double
   * timer. A same-messageId different-bytes re-drive is refused while an
   * attempt is open (and mutates nothing).
   */
  private readonly inFlight = new Map<string, InFlight>();
  private lastReasonCode = "USER_REQUESTED";

  // ---- idle timer fencing (generation counter, 14.4) ----
  private idleTimerGeneration = 0;

  // ---- serialization flag + FIFO queue of deferred turns ----
  private mutating = false;
  private readonly mutationQueue: (() => void)[] = [];

  // ---- durable publish chain (records land in stateSequence order) ----
  private emitChain: Promise<ChainRecordResult> = Promise.resolve({ ok: true });

  constructor(options: ExecutionControlCoordinatorOptions) {
    this.executionId = options.executionId;
    this.dispatchId = options.dispatchId;
    this.policy = options.policy;
    this.clock = options.clock;
    this.scheduler = options.scheduler;
    this.outbox = options.outbox;
    this.emitter = options.emitter;
    this.log = options.logger ?? noopLogger;
  }

  // ============ public surface (14.2) ============

  /**
   * Apply one engine control command. Validates against the accepted
   * revision (22.4: STALE_CONTROL_REVISION for a lower revision,
   * CONTROL_REVISION_CONFLICT for same-revision different bytes, higher
   * supersedes pending older; revisions need not be contiguous), then
   * persists the resulting state records BEFORE resolving (an ack is
   * proof of persistence): `apply` REJECTS when any record of the
   * command's turn failed to persist - the disposition is stamped
   * (becomes replay-visible) only after durability, so an engine
   * re-drive of the same messageId takes the full apply path again. A
   * concurrent duplicate/re-drive of the same messageId while the first
   * attempt is still in flight shares its outcome and never mutates
   * twice. Identical messageId replays AFTER a durable disposition
   * return the stored disposition without reapplying or a timer
   * restart.
   */
  async apply(command: ExecutionControlPayload, commandMessageId: string): Promise<void> {
    if (
      command.executionId !== this.executionId ||
      command.dispatchId !== this.dispatchId
    ) {
      // Fail closed loudly: a foreign command never mutates these
      // controls and is not acked as applied.
      throw new Error(
        `execution.control targets executionId=${command.executionId}/dispatchId=${command.dispatchId}; this coordinator owns ${this.executionId}/${this.dispatchId}`,
      );
    }
    // Mutation turn FIRST (synchronous, lock released inside), then the
    // durability await - the coordinator never holds the lock across it.
    const durability = this.runSerialized(() =>
      this.startApplyTurn(command, commandMessageId),
    );
    const turn = await durability;
    if (turn.kind === "await") await turn.outcome;
  }

  /**
   * Gate one orchestration LEAF operation (model call, tool side effect,
   * helper admission, Git effect). Resolves immediately while RUNNING;
   * parks while HOLD_REQUESTED/HELD. A parked waiter wakes on CONTINUE
   * with a real lease (and counts as an active leaf from then on) and
   * rejects on stop/deadline/cancel with HoldAbortedError (14.3: never
   * leave parked promises hanging). Callers MUST release in try/finally;
   * releases are crash-safe (double release is a no-op).
   */
  enterOperation(point: SafePoint): Promise<OperationLease> {
    return new Promise<OperationLease>((resolve, reject) => {
      this.enqueueTurn(() => {
        if (this.stopped !== null) {
          reject(new HoldAbortedError(this.stopped));
          return;
        }
        if (this.deadlineExpired) {
          reject(new HoldAbortedError("DEADLINE_EXPIRED"));
          return;
        }
        if (this.effectiveState === "RUNNING") {
          this.activeLeaves += 1;
          resolve(this.makeLease(point));
          return;
        }
        // Gate closed (HOLD_REQUESTED/HELD): park. The waiter does NOT
        // count as an active leaf until it is WOKEN (see wakeAllParkers),
        // so HELD can publish while waiters are parked (14.3: a parked
        // continuation never prevents HELD).
        this.parkers.push({ point, resolve, reject });
      });
    });
  }

  /**
   * Admit the ONE pending interaction (no queue in V1, 14.4/22.5).
   * Returns false when a slot exists (reject concurrent admissions), the
   * execution is stopped, or the deadline expired. Accepted chat while
   * HELD cancels the idle clock and publishes INTERACTION_ACTIVITY at
   * the unchanged revision.
   */
  beginInteraction(interactionId: string): boolean {
    return this.runSerializedTurn(() => {
      if (
        this.pendingInteractionId !== null ||
        this.stopped !== null ||
        this.deadlineExpired
      ) {
        return false;
      }
      this.pendingInteractionId = interactionId;
      if (this.effectiveState === "HELD") {
        this.clearIdleClock();
        void this.publishIdleClockState(IDLE_REASON.INTERACTION_ACTIVITY);
      }
      return true;
    });
  }

  /**
   * Clear the interactive slot. While HELD, arms a NEW FULL idle interval
   * from now (never the remainder, 14.4) and publishes INTERACTION_SETTLED
   * at the unchanged revision. A mismatched id is a no-op (a duplicate
   * settlement must not rearm again, 22.4).
   */
  endInteraction(interactionId: string): void {
    this.runSerializedTurn(() => {
      if (this.pendingInteractionId !== interactionId) return;
      this.pendingInteractionId = null;
      if (this.effectiveState === "HELD" && this.stopped === null && !this.deadlineExpired) {
        this.armIdleTimerFromNow();
        void this.publishIdleClockState(IDLE_REASON.INTERACTION_SETTLED);
      }
    });
  }

  /**
   * Track the confirmation inhibitor (22.7). CONFIRMATION_REQUIRED adds
   * the pending request ID (inhibits idle resume while HELD - the SDK
   * tracks the inhibitor, not the engine-stamped 120 s countdown) and
   * cancels the armed clock. A terminal resolution clears it; when the
   * LAST inhibitor clears while HELD, a new full interval is armed and
   * CONFIRMATION_SETTLED publishes. Stale/unknown IDs are no-ops.
   */
  resolveProposal(payload: ExecutionControlRequestResolvedPayload): void {
    this.runSerializedTurn(() => {
      if (this.stopped !== null || this.deadlineExpired) return;
      const requestId = payload.controlRequestId;
      if (payload.status === "CONFIRMATION_REQUIRED") {
        this.pendingControlRequestIds.add(requestId);
        // Publish CONFIRMATION_PENDING on the 0->1 transition only - a
        // second inhibitor while one is pending changes no clock state.
        if (
          this.pendingControlRequestIds.size === 1 &&
          this.effectiveState === "HELD"
        ) {
          this.clearIdleClock();
          void this.publishIdleClockState(IDLE_REASON.CONFIRMATION_PENDING);
        }
        return;
      }
      if (!this.pendingControlRequestIds.delete(requestId)) return;
      if (
        this.pendingInteractionId === null &&
        this.pendingControlRequestIds.size === 0 &&
        this.effectiveState === "HELD"
      ) {
        this.armIdleTimerFromNow();
        void this.publishIdleClockState(IDLE_REASON.CONFIRMATION_SETTLED);
      }
    });
  }

  /**
   * Connection health (22.5: neither disconnected nor reconciling
   * sessions may auto-resume). false fences the armed timer (the resume
   * instant is retained as an observation); true re-arms toward the SAME
   * instant when still HELD - an already-passed interval resumes on the
   * first tick after authoritative recovery.
   */
  setConnectionReady(ready: boolean): void {
    this.runSerializedTurn(() => {
      if (this.connectionReady === ready) return;
      this.connectionReady = ready;
      if (ready) this.rearmIdleTimerFromExistingDeadline();
      else this.fenceIdleTimer();
    });
  }

  /**
   * Terminal teardown (idempotent): wakes ALL parked waiters (they must
   * not re-enter gated work - the executor's cancel path takes over),
   * fences timers by generation, clears inhibitors and any armed clock.
   * Publishes nothing - terminals are the executor's own frames.
   */
  stop(reason: "CANCEL" | "TERMINAL" | "CHANNEL_LOST"): void {
    this.runSerializedTurn(() => {
      if (this.stopped !== null) return; // idempotent
      this.stopped = HOLD_EXIT_BY_STOP_REASON[reason];
      this.clearIdleClock();
      this.pendingInteractionId = null;
      this.pendingControlRequestIds.clear();
      this.wakeAllParkers(this.stopped);
    });
  }

  /**
   * Deadline expiry: wakes parked waiters and stops admission exactly
   * like stop, but leaves the terminal decision (and its frames) to the
   * executor (plan Task 4).
   */
  markDeadlineExpired(): void {
    this.runSerializedTurn(() => {
      if (this.deadlineExpired) return;
      this.deadlineExpired = true;
      this.clearIdleClock();
      this.pendingInteractionId = null;
      this.pendingControlRequestIds.clear();
      this.wakeAllParkers("DEADLINE_EXPIRED");
    });
  }

  /** Immutable frozen copy with a fresh version + capturedAt (14.2). */
  snapshot(): Readonly<ControlSnapshot> {
    return this.runSerializedTurn(() => this.internalSnapshot());
  }

  // ============ serialized internals ============

  /**
   * Run one mutation turn. The turn closure MUST be synchronous - the
   * coordinator never holds the mutation flag across an await (14.3).
   * Deferred callers queue FIFO and are pumped by the running turn.
   */
  private enqueueTurn(turn: () => void): void {
    if (this.mutating) {
      this.mutationQueue.push(turn);
      return;
    }
    this.mutating = true;
    try {
      turn();
    } finally {
      this.pumpQueue();
    }
  }

  private pumpQueue(): void {
    for (;;) {
      const next = this.mutationQueue.shift();
      if (!next) {
        this.mutating = false;
        return;
      }
      try {
        next();
      } catch (error) {
        // Each turn reports its own errors to its caller's promise; a
        // synchronous throw must never wedge the serialization. Log it so
        // a wedge is diagnosable from the worker log.
        this.log.error(
          "control-state mutation turn threw synchronously (queue continues)",
          { error: error instanceof Error ? error.message : String(error) },
        );
      }
    }
  }

  private runSerialized<T>(turn: () => T): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      this.enqueueTurn(() => {
        try {
          resolve(turn());
        } catch (error) {
          reject(error as Error);
        }
      });
    });
  }

  private runSerializedTurn<T>(turn: () => T): T {
    if (this.mutating) {
      // Re-entrancy would mean the lock spans foreign stack frames - a
      // deadlock-rule violation; fail loudly instead of wedging.
      throw new Error("re-entrant control-state mutation attempt");
    }
    this.mutating = true;
    try {
      return turn();
    } finally {
      this.pumpQueue();
    }
  }

  // ---- `apply` protocol (22.4 binding semantics) ----

  /**
   * One serialized apply turn: dedupe (durable replay / in-flight
   * share), or register a NEW in-flight entry and run the mutation.
   * Returns what the caller should await.
   */
  private startApplyTurn(
    command: ExecutionControlPayload,
    commandMessageId: string,
  ): ApplyTurnOutcome {
    // Identical replay AFTER durability: the stored disposition returns
    // without reapplying or restarting a timer (22.4); the durable
    // record of the stored disposition rides the outbox retransmission.
    if (this.dispositions.has(commandMessageId)) {
      return { kind: "replay" };
    }
    const inFlight = this.inFlight.get(commandMessageId);
    if (inFlight !== undefined) {
      // A concurrent duplicate/re-drive of a messageId whose durability
      // is still open joins the FIRST attempt: it settles when the
      // leader's durability settles and never mutates twice.
      if (inFlight.fingerprint === JSON.stringify(command)) {
        return { kind: "await", outcome: inFlight.outcome };
      }
      // Same wire messageId, different bytes: the duplicate is refused
      // (the leader owns the in-flight mutation) without touching state;
      // the engine's ack timeout is the retry path.
      return {
        kind: "await",
        outcome: Promise.reject(
          new Error(
            `control command ${commandMessageId} is in flight with different bytes`,
          ),
        ),
      };
    }
    const fingerprint = JSON.stringify(command);
    let resolveEntry!: () => void;
    let rejectEntry!: (error: Error) => void;
    const outcome = new Promise<void>((resolve, reject) => {
      resolveEntry = resolve;
      rejectEntry = reject;
    });
    const entry: InFlight = {
      fingerprint,
      outcome,
      resolve: (): void => resolveEntry(),
      reject: (error: Error): void => rejectEntry(error),
    };
    this.inFlight.set(commandMessageId, entry);
    this.applyTurn(command, commandMessageId, fingerprint, entry);
    return { kind: "await", outcome: entry.outcome };
  }

  /**
   * The mutation turn for one freshly registered command attempt: it
   * mutates state synchronously, collects the turn's durability record
   * verdicts, and settles the command's outcome through
   * {@link observeTurnRecords}. The turn NEVER stamps the replay
   * disposition - the durability records do (22.4: persist before apply
   * resolves/acks).
   */
  private applyTurn(
    command: ExecutionControlPayload,
    commandMessageId: string,
    fingerprint: string,
    entry: InFlight,
  ): void {
    // Every durability record of THIS turn (in emission order).
    const records: Promise<ChainRecordResult>[] = [];
    const collect = (observed: Promise<ChainRecordResult>): void => {
      records.push(observed);
    };

    // HOLD requires holdPolicy matching the session policy; CONTINUE
    // omits it (22.4).
    if (
      command.action === "HOLD" &&
      (command.holdPolicy == null ||
        command.holdPolicy.idleResumeAfterSeconds !==
          this.policy.idleResumeAfterSeconds)
    ) {
      collect(this.publishRejection(commandMessageId, {
        reasonCode: command.reasonCode,
        errorCode: CONTROL_ERROR_CODE.INVALID_MESSAGE,
        rejectedControlRevision: command.controlRevision,
      }));
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }

    // Delayed commands after terminal/deadline are rejected with
    // EXECUTION_TERMINAL and cannot reopen the execution (22.4/22.8).
    if (this.stopped !== null || this.deadlineExpired) {
      collect(this.publishRejection(commandMessageId, {
        reasonCode: command.reasonCode,
        errorCode: CONTROL_ERROR_CODE.EXECUTION_TERMINAL,
        rejectedControlRevision: command.controlRevision,
      }));
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }

    // Revision binding (22.4): a lower revision is stale.
    if (command.controlRevision < this.acceptedControlRevision) {
      collect(this.publishRejection(commandMessageId, {
        reasonCode: command.reasonCode,
        errorCode: CONTROL_ERROR_CODE.STALE_CONTROL_REVISION,
        rejectedControlRevision: command.controlRevision,
      }));
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }
    const accepted = this.acceptedCommand;
    if (
      accepted !== null &&
      command.controlRevision === accepted.revision &&
      fingerprint !== accepted.fingerprint
    ) {
      // Same revision, conflicting command bytes (22.4).
      collect(this.publishRejection(commandMessageId, {
        reasonCode: command.reasonCode,
        errorCode: CONTROL_ERROR_CODE.CONTROL_REVISION_CONFLICT,
        rejectedControlRevision: command.controlRevision,
      }));
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }

    // Accept: bind the revision marker, then transition. The replay
    // disposition is stamped ONLY by the durability records, never here.
    this.acceptedControlRevision = command.controlRevision;
    this.acceptedCommand = {
      revision: command.controlRevision,
      fingerprint,
      reasonCode: command.reasonCode,
      commandMessageId,
    };
    this.lastReasonCode = command.reasonCode;

    if (command.action === "HOLD") {
      if (this.effectiveState === "HELD") {
        // Idempotent observation (22.4): a new revision over the SAME
        // state, with NO idle reset from a repeated HOLD.
        collect(this.publishTransition("HELD", {
          reasonCode: command.reasonCode,
          correlationId: commandMessageId,
        }));
        this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
        return;
      }
      // HOLD_REQUESTED is visible while draining, never mislabeled HELD;
      // the gate closes BEFORE admission so new leaves stop and running
      // leaves complete (22.5). When the drain publishes HELD in this
      // turn (zero leaves), that record joins the SAME turn set: the
      // command's disposition settles only when BOTH records are durable
      // (a mid-turn failure must never resolve as success).
      this.effectiveState = "HOLD_REQUESTED";
      collect(this.publishTransition("HOLD_REQUESTED", {
        reasonCode: command.reasonCode,
        correlationId: commandMessageId,
      }));
      this.checkDrainPublishHeld(collect);
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }

    // CONTINUE.
    if (this.effectiveState === "RUNNING") {
      // Idempotent observation (22.4).
      collect(this.publishTransition("RUNNING", {
        reasonCode: command.reasonCode,
        correlationId: commandMessageId,
      }));
      this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
      return;
    }
    // Withdraw the pending hold (even before HELD), re-open the gate,
    // wake parked continuations in the same execution (22.5): no new
    // attempt, no new workspace.
    this.clearIdleClock();
    this.heldAtSafePoint = null;
    this.effectiveState = "RUNNING";
    collect(this.publishTransition("RUNNING", {
      reasonCode: command.reasonCode,
      correlationId: commandMessageId,
    }));
    this.wakeAllParkers("CONTINUE");
    this.observeTurnRecords(records, entry, fingerprint, commandMessageId);
  }

  /**
   * Settle one command's outcome from its turn's durability records: the
   * FIRST record failure rejects `apply` (the command stays NOT applied
   * and its disposition is never stamped - a re-drive re-runs the FULL
   * apply path); full success stamps the disposition (replay-visible)
   * and resolves `apply`.
   */
  private observeTurnRecords(
    records: Promise<ChainRecordResult>[],
    entry: InFlight,
    fingerprint: string,
    commandMessageId: string,
  ): void {
    if (records.length === 0) {
      // No records to persist: nothing can fail - settle clean.
      this.settleCommandOutcome(commandMessageId, fingerprint, entry, undefined);
      return;
    }
    let remaining = records.length;
    let settledTurn = false;
    for (const record of records) {
      void record.then((result) => {
        if (settledTurn) return;
        if (!result.ok) {
          settledTurn = true;
          this.settleCommandOutcome(
            commandMessageId,
            fingerprint,
            entry,
            controlPersistError(result.error),
          );
          return;
        }
        remaining -= 1;
        if (remaining === 0) {
          settledTurn = true;
          this.settleCommandOutcome(commandMessageId, fingerprint, entry, undefined);
        }
      });
    }
  }

  /**
   * Pop the in-flight entry and settle the deferred outcome. On success
   * the disposition is stamped (replay-visible per 22.4); on failure it
   * stays unstamped - the engine's re-drive retries the whole apply.
   */
  private settleCommandOutcome(
    commandMessageId: string,
    fingerprint: string,
    entry: InFlight,
    error: Error | undefined,
  ): void {
    if (this.inFlight.get(commandMessageId) === entry) {
      this.inFlight.delete(commandMessageId);
    }
    if (error === undefined) {
      this.dispositions.set(commandMessageId, fingerprint);
      entry.resolve();
    } else {
      entry.reject(error);
    }
  }

  // ---- leaf admission + drain (22.5) ----

  private makeLease(point: SafePoint): OperationLease {
    let released = false;
    return {
      // Arrow method: `this` stays bound to the coordinator instance.
      release: (): void => {
        if (released) return; // double release: guarded no-op
        released = true;
        this.releaseLeaf(point);
      },
    };
  }

  private releaseLeaf(point: SafePoint): void {
    this.runSerializedTurn(() => {
      this.lastLeaseSafePoint = point;
      if (this.activeLeaves > 0) this.activeLeaves -= 1;
      this.checkDrainPublishHeld();
    });
  }

  /**
   * HELD publishes only at zero active orchestration leaves (22.5): at
   * the nearest safe point, never while a leaf could still mutate the
   * checkout. Rechecked on every release and after HOLD with zero leaves.
   * Fires the idle timer at the HELD transition (idle time begins when
   * HELD, not HOLD_REQUESTED, 22.5). When a `sink` is given (a command
   * turn is mid-run), the HELD record joins that turn's durability set;
   * from the leaf-release path it rides the chain unobserved.
   */
  private checkDrainPublishHeld(
    sink?: (observed: Promise<ChainRecordResult>) => void,
  ): void {
    if (
      this.effectiveState !== "HOLD_REQUESTED" ||
      this.activeLeaves > 0 ||
      this.stopped !== null ||
      this.deadlineExpired
    ) {
      return;
    }
    this.effectiveState = "HELD";
    this.heldAtSafePoint = this.lastLeaseSafePoint;
    // Idle time begins when HELD (22.5): arm the clock BEFORE publishing
    // so the HELD payload carries idleResumeAt.
    this.armIdleTimerFromNow();
    const accepted = this.acceptedCommand;
    const observed = this.publishTransition("HELD", {
      reasonCode: accepted?.reasonCode ?? this.lastReasonCode,
      correlationId: accepted?.commandMessageId,
    });
    if (sink !== undefined) sink(observed);
  }

  /** Wake every parked waiter: CONTINUE hands out real leases and the
   * woken waiter counts as an active leaf from its wake onward; abort
   * exits reject with HoldAbortedError (14.3). */
  private wakeAllParkers(reason: HoldExitReason): void {
    const waiters = this.parkers.splice(0, this.parkers.length);
    for (const waiter of waiters) {
      if (reason === "CONTINUE") {
        this.activeLeaves += 1;
        waiter.resolve(this.makeLease(waiter.point));
      } else {
        waiter.reject(new HoldAbortedError(reason));
      }
    }
  }

  // ---- idle clock (14.4 / 22.5) ----

  /** Fence the scheduled callback only (disconnect): the resume instant
   * is retained so reconnect re-arms toward the SAME instant (14.4). */
  private fenceIdleTimer(): void {
    this.idleTimerGeneration += 1;
  }

  /** Cancel the clock entirely (inhibitors, CONTINUE, teardown). */
  private clearIdleClock(): void {
    this.idleTimerGeneration += 1;
    this.idleResumeAt = null;
  }

  /** Arm a NEW FULL idle interval from the current monotonic now (14.4). */
  private armIdleTimerFromNow(): void {
    this.armIdleTimerAt(this.clock.now() + this.policy.idleResumeAfterSeconds * 1000);
  }

  /** Re-arm toward the UNCHANGED resume instant (reconnect, 14.4). */
  private rearmIdleTimerFromExistingDeadline(): void {
    if (
      this.effectiveState !== "HELD" ||
      this.stopped !== null ||
      this.deadlineExpired ||
      this.idleResumeAt === null
    ) {
      return;
    }
    this.armIdleTimerAt(Date.parse(this.idleResumeAt));
  }

  private armIdleTimerAt(resumeAtMs: number): void {
    if (
      this.effectiveState !== "HELD" ||
      this.stopped !== null ||
      this.deadlineExpired ||
      this.pendingInteractionId !== null ||
      this.pendingControlRequestIds.size > 0
    ) {
      return; // an inhibitor holds: the clock stays disarmed
    }
    this.idleTimerGeneration += 1;
    const generation = this.idleTimerGeneration;
    this.idleResumeAt = new Date(resumeAtMs).toISOString();
    if (!this.connectionReady) return; // reconnect re-arms toward this instant
    const delayMs = Math.max(0, resumeAtMs - this.clock.now());
    this.scheduler.schedule(delayMs, () => this.runIdleTimerCallback(generation));
  }

  /** Fenced callback: a stale generation (cancelled/rearmed) is a no-op. */
  private runIdleTimerCallback(generation: number): void {
    this.runSerializedTurn(() => {
      if (generation !== this.idleTimerGeneration) return; // fenced
      const state = this.internalSnapshot();
      if (mayAutoResume(state, this.clock.now())) this.autoResume();
      else this.log.debug("idle timer fired but mayAutoResume is false");
    });
  }

  /**
   * Auto-resume (22.4/22.5): RUNNING at the UNCHANGED accepted engine
   * revision, a new stateSequence, reason HOLD_IDLE_EXPIRED, no
   * correlationId (a spontaneous timer event never fabricates a
   * revision), then the gate wakes continuations.
   */
  private autoResume(): void {
    this.effectiveState = "RUNNING";
    this.heldAtSafePoint = null;
    this.clearIdleClock();
    void this.publishTransition("RUNNING", {
      reasonCode: IDLE_REASON.HOLD_IDLE_EXPIRED,
    });
    this.wakeAllParkers("CONTINUE");
  }

  // ---- publishing (durability before liveness) ----

  private publishTransition(
    status: HoldState,
    context: { reasonCode: string; correlationId?: string },
  ): Promise<ChainRecordResult> {
    this.lastReasonCode = context.reasonCode;
    const payload = this.protocolStatePayload(status);
    this.stateSequence += 1;
    payload.stateSequence = this.stateSequence;
    return this.chainPublish(payload, context.correlationId ?? null);
  }

  /**
   * A HELD idle-clock change (22.4): same accepted revision, a new
   * stateSequence, an INTERACTION_ACTIVITY/INTERACTION_SETTLED or
   * CONFIRMATION_PENDING/CONFIRMATION_SETTLED reason code.
   */
  private publishIdleClockState(reasonCode: string): Promise<ChainRecordResult> {
    this.lastReasonCode = reasonCode;
    const payload = this.protocolStatePayload("HELD");
    this.stateSequence += 1;
    payload.stateSequence = this.stateSequence;
    return this.chainPublish(payload, null);
  }

  /**
   * Publish a REJECTED state for a refused command (22.4: the actual
   * overlay is preserved, which may be RUNNING) and persist it as the
   * command's disposition record - the replay disposition is stamped by
   * the durability observation, never inside the turn.
   */
  private publishRejection(
    commandMessageId: string,
    rejection: ControlRejection,
  ): Promise<ChainRecordResult> {
    this.lastReasonCode = rejection.reasonCode;
    // REJECTED preserves the actual overlay, which may be RUNNING (22.4).
    const payload = this.protocolStatePayload(this.effectiveState);
    payload.status = "REJECTED";
    payload.rejectedControlRevision = rejection.rejectedControlRevision;
    payload.errorCode = rejection.errorCode;
    this.stateSequence += 1;
    payload.stateSequence = this.stateSequence;
    return this.chainPublish(payload, commandMessageId);
  }

  /**
   * Build the wire payload (22.4 fields ONLY - the internal fence fields
   * never cross the wire). `safePoint` is present on HELD; `idleResumeAt`
   * only while HELD with the clock armed (inhibitors null it).
   */
  private protocolStatePayload(status: HoldState): ExecutionControlStatePayload {
    const isHeld = status === "HELD";
    return {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      // Auto-resume retains the accepted revision - never fabricate one.
      controlRevision: this.acceptedControlRevision,
      stateSequence: 0, // stamped by the publication callers
      status,
      effectiveState: isHeld ? "HELD" : status,
      reasonCode: this.lastReasonCode,
      changedAt: new Date(this.clock.now()).toISOString(),
      idleResumeAt: isHeld ? this.idleResumeAt : null,
      safePoint: isHeld ? this.heldAtSafePoint : null,
      rejectedControlRevision: null,
      errorCode: null,
    };
  }

  /**
   * Persist, THEN publish to the live emitter - chained so durable
   * records land in exactly stateSequence order. The awaited write is
   * only the durable seam's own (never a leased/model/ack path).
   *
   * Per-record verdicts are CARRIED in the chain value, never swallowed
   * and never chain poison: the owning command's turn observes them (the
   * first record failure rejects that command's `apply`), a record's
   * failure never denies a LATER record its own persist attempt (the
   * failed record's persistence is retried by the engine's re-drive and
   * by the outbox's unacknowledged retransmission), and records with no
   * observer of their own (idle-clock publishes, drain HELD after their
   * command settled) log their failure loudly instead.
   */
  private chainPublish(
    payload: ExecutionControlStatePayload,
    commandMessageId: string | null,
  ): Promise<ChainRecordResult> {
    const run = (): Promise<void> =>
      this.outbox.persistControlState(payload, commandMessageId).then(() => {
        this.emitter.publish(payload);
      });
    // The previous record's verdict (a value, never a rejection) does
    // not deny this record its own persist attempt.
    const attempt = this.emitChain.then(() => run());
    const observed = attempt.then(
      (): ChainRecordResult => ({ ok: true }),
      (error: unknown): ChainRecordResult => ({ ok: false, error }),
    );
    // Unhandled-rejection guard + loud log for every record: a failed
    // persist must never crash the worker and never silently vanish.
    void observed.then((result) => {
      if (!result.ok) this.logChainFailure(payload, result.error);
    });
    this.emitChain = observed;
    return observed;
  }

  private logChainFailure(payload: ExecutionControlStatePayload, error: unknown): void {
    this.log.error(
      `control-state persist FAILED (execution ${payload.executionId} seq ${payload.stateSequence} status ${payload.status}): ${error instanceof Error ? error.message : String(error)}`,
    );
  }

  // ---- snapshots (14.2) ----

  private internalSnapshot(): ControlSnapshot {
    this.snapshotVersion += 1;
    const snapshot: ControlSnapshot = {
      effectiveState: this.effectiveState,
      acceptedControlRevision: this.acceptedControlRevision,
      stateSequence: this.stateSequence,
      idleResumeAt: this.effectiveState === "HELD" ? this.idleResumeAt : null,
      pendingInteractionId: this.pendingInteractionId,
      pendingControlRequestIds: Object.freeze([...this.pendingControlRequestIds]),
      connectionReady: this.connectionReady,
      stopped: this.stopped !== null,
      deadlineExpired: this.deadlineExpired,
      version: this.snapshotVersion,
      capturedAt: new Date(this.clock.now()).toISOString(),
      safePoint: this.heldAtSafePoint,
      lastReasonCode: this.lastReasonCode,
    };
    return Object.freeze(snapshot);
  }
}
