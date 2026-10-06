// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * InteractiveController (design 14.2-14.6, protocol 22.4-22.6): the
 * embedded reactive chat loop beside the orchestration loop.
 *
 * ONE controller per execution, composed by the OrchestrationExecutor
 * after the admitted orchestration start: the SAME attempt, the SAME
 * shared BudgetController, the SAME attempt-level AttemptModelScheduler
 * (14.5: no second allocation), but its OWN model ADAPTER instance, a
 * SEPARATE redacted history, and ONLY the interaction allowlist bindings
 * (the orchestrator's transcript object is never shared).
 *
 * Each user message:
 *   1. beginInteraction through the coordinator (ONE pending slot);
 *      duplicate interactionId + identical bytes REPLAY the stored
 *      outcome (no second model call); conflicting bytes fail closed.
 *   2. the bounded turn runs through the scheduler as INTERACTION
 *      (priority; the deadline INCLUDES scheduler wait), with a
 *      generation-fenced deadline timer arming the 22.6 response
 *      deadline. The deadline/stop aborts the model's AbortSignal
 *      (provider support is adapter-dependent: an abort-unsupported
 *      provider keeps the scheduler permit until the actual call
 *      settles - late results are fenced from output and tools).
 *   3. streaming fragments pass capture/redaction before the onOutput
 *      callback (execution.interaction.delta); the final response is
 *      assembled ONCE through the shared TurnExecutor path.
 *   4. the outcome (complete/failed) is persisted through the outbox
 *      BEFORE the wire publish; interaction failure alone never fails
 *      the attempt.
 *
 * Chat does not auto-hold (22.5): a snapshot question while RUNNING
 * creates NO HOLD, and the loop is never gated by the orchestrator's
 * hold gate - it runs while HELD. contentMode NONE disables chat
 * content entirely (CAPTURE_BLOCKED) while direct controls stay
 * permitted. stop() fails the pending interaction and rejects pending
 * proposal waits (never left hanging - 14.3).
 */
import { randomUUID } from "node:crypto";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type {
  ExecutionControl,
  MonotonicClock,
  TimerScheduler,
} from "./ExecutionControlCoordinator.js";
import type { AttemptModelScheduler } from "./AttemptModelScheduler.js";
import type {
  ExecutionInteractionPayload,
  ExecutionControlRequestResolvedPayload,
  ExecutionControlRequestPayload,
  ExecutionInteractionDeltaPayload,
  ExecutionInteractionCompletePayload,
  ExecutionInteractionFailedPayload,
  InteractionPolicy,
} from "../protocol/unifiedFrames.js";
import type { Task, Logger } from "../models/index.js";
import type { ChatModel, Tool } from "../executor/types.js";
import type { BudgetController } from "../orchestration/BudgetController.js";
import type { SafeExecutionEvent } from "./SafeExecutionEvent.js";
import type { ExecutionSnapshot } from "./ExecutionSnapshot.js";
import {
  buildInteractionTools,
  type InteractionToolContext,
} from "./interactionTools.js";

/** 22.6 failure codes verbatim. */
export const INTERACTION_ERROR_CODE = {
  INTERACTION_TIMEOUT: "INTERACTION_TIMEOUT",
  MODEL_ERROR: "MODEL_ERROR",
  OUTPUT_LIMIT_EXCEEDED: "OUTPUT_LIMIT_EXCEEDED",
  TOKEN_USAGE_UNAVAILABLE: "TOKEN_USAGE_UNAVAILABLE",
  EXECUTION_TERMINAL: "EXECUTION_TERMINAL",
  EXECUTION_CANCELLED: "EXECUTION_CANCELLED",
  HOST_STATE_LOST: "HOST_STATE_LOST",
  CAPTURE_BLOCKED: "CAPTURE_BLOCKED",
} as const;
export type InteractionErrorCode =
  (typeof INTERACTION_ERROR_CODE)[keyof typeof INTERACTION_ERROR_CODE];

/** The outbox seam for the controller's durable records (14.2 ownership
 * table; 22.3: persist before send). Tests inject recording stubs. */
export interface InteractionOutbox {
  persistInteractionComplete(payload: ExecutionInteractionCompletePayload): Promise<void>;
  persistInteractionFailed(payload: ExecutionInteractionFailedPayload): Promise<void>;
  persistControlRequest(payload: ExecutionControlRequestPayload): Promise<void>;
}

/** The wire emitter seam (persist THEN emit - the concrete senders ride
 * the dedicated Agent Channel). */
export interface InteractionEmitter {
  sendDelta(payload: ExecutionInteractionDeltaPayload): Promise<void>;
  sendComplete(payload: ExecutionInteractionCompletePayload): Promise<void>;
  sendFailed(payload: ExecutionInteractionFailedPayload): Promise<void>;
  sendControlRequest(payload: ExecutionControlRequestPayload): Promise<void>;
}

export interface InteractiveControllerOptions {
  executionId: string;
  dispatchId: string;
  /** The effective immutable session policy (22.2). */
  interactionPolicy: InteractionPolicy;
  /** The ONE coordinator (the orchestration loop gates on it too). */
  control: ExecutionControl;
  /** The attempt-level model scheduler (the SAME permit - interaction
   * takes priority at the next boundary, 14.5). */
  modelScheduler: AttemptModelScheduler;
  /** The controller's OWN model adapter (separate adapter instance,
   * same orchestrator model config - never a shared rebinding). */
  model: ChatModel;
  /** The current immutable snapshot view (the executor's publisher). */
  getSnapshot(): Readonly<ExecutionSnapshot>;
  /** The bounded recent safe events (the executor's ring). */
  recentEvents(limit: number): ReadonlyArray<SafeExecutionEvent>;
  /** The shared per-attempt budget (14.5: same controller). */
  budget?: BudgetController;
  /** The durable outbox seam (persist before publish). */
  outbox: InteractionOutbox;
  /** The wire emitter (the dedicated-channel senders). */
  emitter: InteractionEmitter;
  /** Injected clock for deadline arithmetic. */
  clock?: MonotonicClock;
  /** Injected one-shot timer scheduler (fenced by generation). */
  scheduler?: TimerScheduler;
  /** Observability seam: one settled interaction's attributed usage. */
  onUsageSettled?: (usage: {
    interactionId: string;
    usage: ExecutionInteractionCompletePayload["usage"];
    usageStatus: "KNOWN" | "UNKNOWN";
  }) => void;
  logger?: Logger;
}

const noopLogger: Logger = { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} };

/** The safe interaction prompt (14.6): scope, prohibited actions,
 * allowed tools, the current immutable snapshot. No orchestrator
 * scratchpad, no chain of thought, no credentials. */
function interactionSystemPrompt(
  snapshot: Readonly<ExecutionSnapshot>,
  toolNames: readonly string[],
): string {
  return [
    "You are the interactive assistant embedded in a Myrmec orchestration execution.",
    "Answer the user's questions about THIS execution only, using the read-only",
    "tools you are given. You cannot modify the workspace, run commands, invoke",
    "helpers, or change policy - you may only PROPOSE hold/continue/cancel through",
    "the engine; the engine's disposition (which may require the user's separate",
    "confirmation) is the only outcome, and CANCEL answers are pending until the",
    "user confirms them through the engine.",
    "Snapshot data is stale at view time: report the version and capturedAt you",
    "see, never claim a live lock or a final commit from it.",
    "",
    "Execution snapshot: " + JSON.stringify(snapshot),
    "",
    "Allowed tools: " + toolNames.join(", "),
  ].join("\n");
}

/** Fragment sanity pass (14.6: "when secret filtering cannot safely
 * inspect a fragment, buffer and withhold it"). V1 is a bounded pattern
 * pass; a fragment that fails inspection is withheld from the wire (the
 * complete outcome is authoritative when deltas were dropped). */
const SECRET_PATTERNS: RegExp[] = [
  /myr_[A-Za-z0-9+/=_-]{8,}/, // registration/project keys
  /sk-[A-Za-z0-9_-]{8,}/, // API keys (OpenAI-style)
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/, // key material
  /eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}/, // JWT shape
];

function fragmentMayEmit(text: string): boolean {
  for (const pattern of SECRET_PATTERNS) {
    if (pattern.test(text)) {
      return false;
    }
  }
  return true;
}

/** The final answer must ALSO pass the same inspection (a long secret
 * can assemble across withheld/passed fragments). */
function answerMayComplete(text: string): boolean {
  for (const pattern of SECRET_PATTERNS) {
    if (pattern.test(text)) {
      return false;
    }
  }
  return true;
}

/** One interaction's in-flight state (ONE per controller - the pending slot). */
interface PendingInteraction {
  payload: ExecutionInteractionPayload;
  fingerprint: string;
  /** Generation-fenced deadline timer. */
  deadlineTimerGeneration: number;
  /** The provider-call abort signal (the third model argument). */
  abort: AbortController;
  /** Delta index state (22.6: starts at 0 per interaction). */
  nextDeltaIndex: number;
  /** The pending proposals' control request ids (the 22.6 payload). */
  controlRequestIds: string[];
  /** Usage aggregation across the turn (22.6: partial known usage
   * always reported; never estimated). */
  usagePrompt: number | null;
  usageCompletion: number | null;
  anyUsageReported: boolean;
  /** The sanitized fragment accumulation (14.6: the authoritative
   * answer is built from the fragments that passed inspection; a
   * withheld fragment never crosses the wire and never joins). */
  sanitizedAnswer: string;
  /** The settlement deferred: resolved by the FIRST settle (turn
   * outcome / deadline / stop); `handle` races against the turn. */
  settlement: Promise<SettledOutcome>;
  markSettled: (outcome: SettledOutcome) => void;
  /** Whether the outcome already settled (fences late results). */
  settled: boolean;
}

/** A proposal wait (settled by the engine's resolution frame; rejected
 * on stop - never left hanging). */
interface PendingProposal {
  requestId: string;
  resolve: (value: ExecutionControlRequestResolvedPayload) => void;
  reject: (error: Error) => void;
}

/** The settled-outcome replay (22.6: duplicate + identical bytes). */
type SettledOutcome =
  | ExecutionInteractionCompletePayload
  | ExecutionInteractionFailedPayload;

/**
 * The embedded interactive controller. See the module doc.
 */
export class InteractiveController {
  private readonly executionId: string;
  private readonly dispatchId: string;
  private readonly policy: InteractionPolicy;
  private readonly control: ExecutionControl;
  private readonly modelScheduler: AttemptModelScheduler;
  private readonly model: ChatModel;
  private readonly snapshotOf: () => Readonly<ExecutionSnapshot>;
  private readonly eventsOf: (limit: number) => ReadonlyArray<SafeExecutionEvent>;
  private readonly budget?: BudgetController;
  private readonly outbox: InteractionOutbox;
  private readonly emitter: InteractionEmitter;
  private readonly clock: MonotonicClock;
  private readonly timers: TimerScheduler;
  private readonly onUsageSettled?: InteractiveControllerOptions["onUsageSettled"];
  private readonly log: Logger;

  /** SEPARATE redacted history (user/assistant pairs only). */
  private history: Array<{ role: "user" | "assistant"; content: string }> = [];
  /** The settled-outcome map: interactionId -> outcome (durable replay). */
  private readonly replay = new Map<string, SettledOutcome>();
  /** The fingerprints behind the replay entries (conflict detection). */
  private readonly fingerprints = new Map<string, string>();
  /** The ONE in-flight interaction (null = slot free). */
  private pending: PendingInteraction | null = null;
  /** In-flight proposal waits (rejected on stop). */
  private readonly pendingProposals = new Map<string, PendingProposal>();
  /** Teardown fence. */
  private stoppedReason: string | null = null;

  constructor(options: InteractiveControllerOptions) {
    this.executionId = options.executionId;
    this.dispatchId = options.dispatchId;
    this.policy = options.interactionPolicy;
    this.control = options.control;
    this.modelScheduler = options.modelScheduler;
    this.model = options.model;
    this.snapshotOf = options.getSnapshot;
    this.eventsOf = options.recentEvents;
    this.budget = options.budget;
    this.outbox = options.outbox;
    this.emitter = options.emitter;
    this.clock = options.clock ?? { now: () => Date.now() };
    this.timers = options.scheduler ?? {
      schedule: (delayMs, callback) => {
        setTimeout(callback, delayMs).unref?.();
      },
    };
    this.onUsageSettled = options.onUsageSettled;
    this.log = options.logger ?? noopLogger;
  }

  /** The interaction allowlist (fresh bindings via the tool context). */
  get tools(): Tool[] {
    return buildInteractionTools(this.toolContext(), this.log);
  }

  /** Whether the controller can still admit new interactions. */
  get stopped(): boolean {
    return this.stoppedReason !== null;
  }

  /**
   * Handle ONE engine-authorized user message (22.6). Duplicate
   * interactionId + identical bytes replay the stored outcome
   * (re-published durably, no second model call); conflicting bytes
   * fail closed (INVALID_MESSAGE); a second CONCURRENT interaction is
   * refused (one pending slot; RESOURCE_IN_USE in the failure message).
   */
  async handle(request: ExecutionInteractionPayload): Promise<void> {
    if (request.executionId !== this.executionId || request.dispatchId !== this.dispatchId) {
      // Identity mismatch is the executor layer's protocol.error duty;
      // the controller only refuses to touch its state.
      this.log.warn(
        "interaction identity mismatch: controller owns " +
          this.executionId + "/" + this.dispatchId,
      );
      return;
    }
    if (this.stoppedReason !== null) {
      await this.settleFail(
        request,
        INTERACTION_ERROR_CODE.EXECUTION_TERMINAL,
        "the execution is done; the interaction cannot run",
      );
      return;
    }
    if (this.policy.enabled === false) {
      // The engine must not send chat to a disabled session (admission
      // is the engine's duty) - fail closed without a model call.
      await this.settleFail(
        request,
        INTERACTION_ERROR_CODE.HOST_STATE_LOST,
        "interaction policy disabled",
      );
      return;
    }

    // ---- replay / conflict (22.6) ----
    const fingerprint = this.fingerprintOf(request);
    const stored = this.replay.get(request.interactionId);
    if (stored) {
      if (this.fingerprints.get(request.interactionId) === fingerprint) {
        // The outcome RE-PUBLISHES durably (the engine may have lost
        // the record) without another model call.
        await this.publishOutcome(stored);
        this.onUsageSettled?.({
          interactionId: request.interactionId,
          usage: stored.usage ?? null,
          usageStatus: stored.usageStatus,
        });
        return;
      }
      await this.settleConflict(
        request,
        INTERACTION_ERROR_CODE.MODEL_ERROR,
        "INVALID_MESSAGE: the re-delivered interactionId carries conflicting bytes",
      );
      return;
    }

    // ---- ONE pending slot (22.5/22.6; no queue in V1) ----
    if (this.pending !== null) {
      await this.settleFail(
        request,
        INTERACTION_ERROR_CODE.HOST_STATE_LOST,
        "RESOURCE_IN_USE: another interaction is pending (no queue in V1)",
      );
      return;
    }
    // contentMode NONE: chat content is disabled - answer nothing
    // (22.6 CAPTURE_BLOCKED) while direct controls stay permitted.
    if (this.policy.contentMode === "NONE") {
      await this.settleFail(
        request,
        INTERACTION_ERROR_CODE.CAPTURE_BLOCKED,
        "content capture is disabled for this session (contentMode NONE)",
      );
      return;
    }

    // ---- admit through the coordinator (the serialization owner) ----
    const admitted = this.control.beginInteraction(request.interactionId);
    if (!admitted) {
      const state = this.control.snapshot();
      const terminal = state.stopped || state.deadlineExpired;
      await this.settleFail(
        request,
        terminal
          ? INTERACTION_ERROR_CODE.EXECUTION_TERMINAL
          : INTERACTION_ERROR_CODE.HOST_STATE_LOST,
        terminal
          ? "the execution is done; the interaction cannot run"
          : "RESOURCE_IN_USE: the interaction slot is occupied",
      );
      return;
    }

    // ---- run the bounded turn ----
    const pendingInteraction = this.startPending(request, fingerprint);
    // The turn loop runs DETACHED: the settlement (the first of the
    // turn outcome, the deadline timer, or stop teardown) resolves
    // `handle`; a late turn result is fenced by `pending.settled`
    // (22.8: an abort-unsupported provider may still hold the permit -
    // its late result never re-runs tools or replaces the answer). The
    // detached turn still releases the scheduler permit and the
    // coordinator slot exactly once (release in the settle path).
    await this.runBoundedTurn(pendingInteraction);
  }

  /**
   * The bounded turn body: the model loop races the settlement deferred
   * (the deadline timer / stop teardown settle it first when they win).
   * Every failure path settles a failed interaction outcome (the
   * interaction failure alone never fails the attempt, 22.6).
   */
  private async runBoundedTurn(pending: PendingInteraction): Promise<void> {
    const turnPromise = this.turnOutcome(pending);
    const race = await Promise.race([
      turnPromise.then(
        (outcome) => ({ source: "turn" as const, outcome }),
        (error: unknown) => ({
          source: "turn" as const,
          outcome: {
            kind: "fail" as const,
            errorCode: INTERACTION_ERROR_CODE.MODEL_ERROR,
            message:
              "interaction turn crashed: " +
              (error instanceof Error ? error.message : String(error)),
          },
        }),
      ),
      pending.settlement.then((outcome) => ({ source: "settled" as const, outcome })),
    ]);
    try {
      if (race.source === "settled") {
        // A deadline/stop teardown settled first: publish the teardown
        // outcome durably (persist BEFORE emit, 22.3); the DETACHED turn
        // is never awaited (the provider call stays behind its own
        // transport timeout; late results are fenced).
        void turnPromise.catch(() => undefined);
        await this.publishOutcome(race.outcome);
        this.replay.set(race.outcome.interactionId, race.outcome);
        this.fingerprints.set(race.outcome.interactionId, pending.fingerprint);
        this.onUsageSettled?.({
          interactionId: race.outcome.interactionId,
          usage: race.outcome.usage ?? null,
          usageStatus: race.outcome.usageStatus,
        });
        return;
      }
      const outcome = race.outcome;
      if (outcome.kind === "complete") {
        await this.completeInteraction(pending, outcome);
      } else {
        await this.failInteraction(pending, outcome.errorCode, outcome.message);
      }
    } finally {
      // Exactly-once slot release on EVERY path (the idle clock re-arms
      // on HELD); the settled slot pointer cleared by the settle paths.
      if (this.pending === pending) {
        this.pending = null;
      }
      // The pending slot pointer clears here; the executor's teardown
      // owns the coordinator's own stop (the runBoundedTurn finally
      // releases the interaction slot exactly once).
      if (this.pending === pending) {
        this.pending = null;
      }
    }
  }

  /**
   * Propose one engine-authorized control (22.7) - the request_* tools'
   * seam. Persists the proposal durably BEFORE the wire emission, then
   * awaits the engine's resolution frame (the executor routes
   * execution.control.request.resolved here). stop() rejects the wait.
   */
  async proposeControl(
    action: "HOLD" | "CONTINUE" | "CANCEL",
    explanation?: string,
  ): Promise<ExecutionControlRequestResolvedPayload> {
    const requestId = randomUUID();
    const payload: ExecutionControlRequestPayload = {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      interactionId: this.pending?.payload.interactionId ?? randomUUID(),
      controlRequestId: requestId,
      action,
      explanation: explanation ?? null,
    };
    // 22.3 persist BEFORE send.
    await this.outbox.persistControlRequest(payload);
    await this.emitter.sendControlRequest(payload);
    if (this.stoppedReason !== null) {
      throw new Error(
        "controller stopped (" +
          this.stoppedReason +
          "): the proposal cannot await a disposition",
      );
    }
    return await new Promise<ExecutionControlRequestResolvedPayload>((resolve, reject) => {
      this.pendingProposals.set(requestId, { requestId, resolve, reject });
    });
  }

  /**
   * Settle an engine disposition for a proposal wait (22.7). The FIRST
   * resolution for a request id settles its wait - CONFIRMATION_REQUIRED
   * included (the tool surfaces the pending state in the answer; the
   * interaction never waits for a user INSIDE the turn). Stale/unknown
   * ids are no-ops.
   */
  settleProposalResolution(payload: ExecutionControlRequestResolvedPayload): void {
    const wait = this.pendingProposals.get(payload.controlRequestId);
    if (!wait) return;
    this.pendingProposals.delete(payload.controlRequestId);
    wait.resolve(payload);
  }

  /**
   * Terminal teardown (14.2 contract): fail the pending interaction
   * (EXECUTION_TERMINAL or EXECUTION_CANCELLED per the reason), reject
   * every pending proposal wait, abort the provider call, fence timers.
   * Durable records are kept. Idempotent.
   */
  async stop(reason: string): Promise<void> {
    if (this.stoppedReason !== null) {
      return; // idempotent teardown
    }
    this.stoppedReason = reason;
    const code = reason === "CANCEL"
      ? INTERACTION_ERROR_CODE.EXECUTION_CANCELLED
      : INTERACTION_ERROR_CODE.EXECUTION_TERMINAL;
    // Abort the provider call FIRST (fences late results from tools).
    const pendingInteraction = this.pending;
    pendingInteraction?.abort.abort();
    // Synthesize the teardown outcome + release the slot BEFORE the
    // durable emit (the waiters unblock immediately; 22.8: a durable
    // pause/terminal tears the controller down like other terminals).
    if (pendingInteraction !== null && !pendingInteraction.settled) {
      pendingInteraction.settled = true;
      pendingInteraction.deadlineTimerGeneration += 1;
      pendingInteraction.markSettled({
        executionId: this.executionId,
        dispatchId: this.dispatchId,
        interactionId: pendingInteraction.payload.interactionId,
        ordinal: pendingInteraction.payload.ordinal,
        error: {
          errorCode: code,
          message:
            "the execution stopped (" + reason + "); the interaction is settled as a failure",
          retryable: false,
        },
        usage: pendingInteraction.anyUsageReported
          ? {
              inputTokens: pendingInteraction.usagePrompt,
              outputTokens: pendingInteraction.usageCompletion,
              modelId: null,
            }
          : null,
        usageStatus: pendingInteraction.anyUsageReported ? "KNOWN" : "UNKNOWN",
        controlRequestIds: [...pendingInteraction.controlRequestIds],
        completedAt: new Date(this.clock.now()).toISOString(),
      });
      // The pending slot releases immediately (the executor's teardown
      // owns the coordinator's own stop (the runBoundedTurn finally
      // releases the interaction slot exactly once).
      if (this.pending === pendingInteraction) {
        this.pending = null;
      }
      // The coordinator's interaction slot releases with the teardown
      // too (22.8: terminal invalidates the pending interaction; the
      // coordinator's stop follows from the executor's teardown, and
      // the slot must not survive it).
      this.control.endInteraction(pendingInteraction.payload.interactionId);
    }
    for (const wait of this.pendingProposals.values()) {
      wait.reject(
        new Error("controller stopped (" + reason + "): the proposal wait is aborted"),
      );
    }
    this.pendingProposals.clear();
  }

  // ---- internals -----------------------------------------------------

  /** Fail-closed settlement for a request that never entered the turn
   * (replay conflict, slot taken, disabled, terminal, capture NONE).
   * The outcome is recorded in the replay map so a re-drive of the SAME
   * request replays the failure instead of re-deriving it. */
  private async settleFail(
    request: ExecutionInteractionPayload,
    errorCode: InteractionErrorCode,
    message: string,
  ): Promise<void> {
    // Do not overwrite an already-settled outcome (the replay wins).
    if (this.replay.has(request.interactionId)) {
      await this.publishOutcome(this.replay.get(request.interactionId) as SettledOutcome);
      return;
    }
    await this.settleConflict(request, errorCode, message);
  }

  /** A FRESH failure record (never the replay-republish guard): used by
   * the conflicting-bytes path, where the stored outcome must NOT
   * shadow the conflict — the engine receives the INVALID_MESSAGE
   * failure for the re-delivered request while the original outcome
   * stays intact in the replay map. */
  private async settleConflict(
    request: ExecutionInteractionPayload,
    errorCode: InteractionErrorCode,
    message: string,
  ): Promise<void> {
    const fingerprint = this.fingerprintOf(request);
    const payload: ExecutionInteractionFailedPayload = {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      interactionId: request.interactionId,
      ordinal: request.ordinal,
      error: {
        errorCode,
        message: message.slice(0, 2000),
        retryable: false,
      },
      usage: null,
      usageStatus: "UNKNOWN",
      controlRequestIds: [],
      completedAt: new Date(this.clock.now()).toISOString(),
    };
    await this.publishOutcome(payload);
    // The conflict does NOT overwrite the original outcome's replay
    // entry - a later identical re-drive replays the ORIGINAL.
    if (!this.replay.has(request.interactionId)) {
      this.replay.set(request.interactionId, payload);
      this.fingerprints.set(request.interactionId, fingerprint);
    }
  }

  /** The tool context bound to the CURRENT pending interaction. */
  private toolContext(): InteractionToolContext {
    const pending = this.pending;
    return {
      getSnapshot: () => this.snapshotOf(),
      getRecentEvents: (limit: number) => this.eventsOf(limit),
      getInteractionUsage: () => {
        if (!pending || !pending.anyUsageReported) return null;
        return {
          inputTokens: pending.usagePrompt,
          outputTokens: pending.usageCompletion,
          modelId: null,
        };
      },
      requestControl: async (action, explanation) => {
        const disposition = await this.proposeControl(action, explanation);
        if (pending && disposition.status === "CONFIRMATION_REQUIRED") {
          // 22.6: the pending confirmation id rides the outcome.
          pending.controlRequestIds.push(disposition.controlRequestId);
        }
        return disposition;
      },
    };
  }

  /** Canonical request bytes for the replay/conflict decision (22.6:
   * "duplicate interactionId + identical bytes"). The idempotent
   * identity + message content ONLY - the transport timestamps
   * (acceptedAt/responseDeadline) vary across an engine re-drive of the
   * SAME request and must not turn a replay into a conflict. */
  private fingerprintOf(request: ExecutionInteractionPayload): string {
    return JSON.stringify([
      request.executionId,
      request.dispatchId,
      request.interactionId,
      request.ordinal,
      request.actorUserId,
      request.message.text,
    ]);
  }

  private startPending(
    request: ExecutionInteractionPayload,
    fingerprint: string,
  ): PendingInteraction {
    let markSettled!: (outcome: SettledOutcome) => void;
    const settlement = new Promise<SettledOutcome>((resolve) => {
      markSettled = resolve;
    });
    const pendingInteraction: PendingInteraction = {
      payload: request,
      fingerprint,
      deadlineTimerGeneration: 0,
      abort: new AbortController(),
      nextDeltaIndex: 0,
      controlRequestIds: [],
      usagePrompt: null,
      usageCompletion: null,
      anyUsageReported: false,
      /** The sanitized fragment accumulation (14.6: the authoritative
       * answer is built from the fragments that passed inspection). */
      sanitizedAnswer: "",
      settlement,
      markSettled,
      settled: false,
    };
    this.pending = pendingInteraction;
    return pendingInteraction;
  }

  /**
   * Run ONE bounded model turn and return its mapped outcome. The turn:
   * arms the deadline timer, binds the allowlist to the CURRENT slot,
   * runs through the scheduler as INTERACTION, and maps the TaskResult
   * onto the 22.6 catalogue.
   */
  private async turnOutcome(
    pending: PendingInteraction,
  ): Promise<
    | { kind: "complete"; answer: string }
    | { kind: "fail"; errorCode: InteractionErrorCode; message: string }
  > {
    this.armDeadline(pending);
    const question = pending.payload.message.text;
    if (Buffer.byteLength(question) > this.policy.maxInputBytes) {
      return {
        kind: "fail",
        errorCode: INTERACTION_ERROR_CODE.MODEL_ERROR,
        message: "the question exceeds maxInputBytes",
      };
    }

    const tools = this.buildTurnTools(pending);
    const toolNames = tools.map((t) => t.name);
    const task: Task = {
      taskId: "interaction-" + pending.payload.interactionId,
      model: "interactive/" + this.dispatchId,
      context: {
        systemPrompt: interactionSystemPrompt(this.snapshotOf(), toolNames),
        messages: [...this.history, { role: "user", content: question }],
        toolNames,
      },
    };

    // The turn's cancellation: the deadline/stop abort flips it, so a
    // late provider response's tool calls are FENCED (14.5/22.8).
    const cancellation = {
      get cancelled(): boolean {
        return pending.abort.signal.aborted;
      },
    };

    let result;
    try {
      result = await this.modelScheduler.invoke(
        "INTERACTION",
        async () =>
          await new TurnExecutor({ logger: this.log }).execute(task, {
            model: this.model,
            tools,
            cancellation,
            maxIterationsOverride: this.policy.maxModelIterations,
            budget: this.budget,
            modelSignal: pending.abort.signal,
            modelSchedulerSource: "INTERACTION",
            onOutput: (fragment) => this.emitFragment(pending, fragment),
            onModelResponseStart: () => {
              // A fresh model response: the authoritative answer is the
              // FINAL response's sanitized content (14.6).
              pending.sanitizedAnswer = "";
            },
          }),
      );
    } catch (error) {
      return {
        kind: "fail",
        errorCode: INTERACTION_ERROR_CODE.MODEL_ERROR,
        message:
          "interaction turn crashed: " +
          (error instanceof Error ? error.message : String(error)),
      };
    }

    // 22.6: partial known usage always reported.
    this.noteUsage(pending, result.usage);

    if (result.status === "COMPLETE") {
      const answer = this.answerOf(pending, (result.completion ?? "").trim());
      if (answer.length === 0) {
        return {
          kind: "fail",
          errorCode: INTERACTION_ERROR_CODE.MODEL_ERROR,
          message: "the model produced no answer content",
        };
      }
      if (!answerMayComplete(answer)) {
        return {
          kind: "fail",
          errorCode: INTERACTION_ERROR_CODE.CAPTURE_BLOCKED,
          message:
            "the assembled answer failed content inspection (secret-shaped text withheld)",
        };
      }
      if (Buffer.byteLength(answer) > this.policy.maxOutputBytes) {
        return {
          kind: "fail",
          errorCode: INTERACTION_ERROR_CODE.OUTPUT_LIMIT_EXCEEDED,
          message: "the answer exceeded maxOutputBytes (" + this.policy.maxOutputBytes + ")",
        };
      }
      return { kind: "complete", answer };
    }
    if (result.status === "CANCELLED") {
      return {
        kind: "fail",
        errorCode: INTERACTION_ERROR_CODE.INTERACTION_TIMEOUT,
        message: "the interaction turn was aborted inside the model loop",
      };
    }
    // FAILED: map the finish reason onto the 22.6 catalogue.
    const finishReason = result.failure?.finishReason ?? "MODEL_ERROR";
    if (
      finishReason === "HELPER_BUDGET_EXCEEDED" ||
      finishReason === "TOKEN_BUDGET_EXCEEDED" ||
      finishReason === "REJECTION_BUDGET_EXCEEDED"
    ) {
      return {
        kind: "fail",
        errorCode: INTERACTION_ERROR_CODE.TOKEN_USAGE_UNAVAILABLE,
        message: "the shared budget is exhausted: " + (result.failure?.message ?? finishReason),
      };
    }
    return {
      kind: "fail",
      errorCode: INTERACTION_ERROR_CODE.MODEL_ERROR,
      message: result.failure?.message ?? "the model turn failed (" + finishReason + ")",
    };
  }

  /** The per-turn tool bindings (the context closures bind THIS slot -
   * never re-read this.pending inside a running turn). */
  private buildTurnTools(pending: PendingInteraction): Tool[] {
    const context: InteractionToolContext = {
      getSnapshot: () => this.snapshotOf(),
      getRecentEvents: (limit: number) => this.eventsOf(limit),
      getInteractionUsage: () => {
        if (!pending.anyUsageReported) return null;
        return {
          inputTokens: pending.usagePrompt,
          outputTokens: pending.usageCompletion,
          modelId: null,
        };
      },
      requestControl: async (action, explanation) => {
        const disposition = await this.proposeControl(action, explanation);
        if (disposition.status === "CONFIRMATION_REQUIRED") {
          pending.controlRequestIds.push(disposition.controlRequestId);
        }
        return disposition;
      },
    };
    return buildInteractionTools(context, this.log);
  }

  /** Arm the 22.6 response-deadline timer (generation-fenced). The
   * deadline INCLUDES scheduler wait (22.6): the timer arms at admission,
   * not at the first model invocation. */
  private armDeadline(pending: PendingInteraction): void {
    pending.deadlineTimerGeneration += 1;
    const generation = pending.deadlineTimerGeneration;
    const deadlineMs = Date.parse(pending.payload.responseDeadline);
    const delayMs = Math.max(0, deadlineMs - this.clock.now());
    this.timers.schedule(delayMs, () => {
      if (pending.deadlineTimerGeneration !== generation || pending.settled) {
        return; // fenced
      }
      // Abort the provider call; the interaction fails visibly while an
      // abort-unsupported provider may still hold the permit briefly.
      pending.abort.abort();
      void this.failInteraction(
        pending,
        INTERACTION_ERROR_CODE.INTERACTION_TIMEOUT,
        "the response deadline elapsed (deadline includes scheduler wait)",
      );
    });
  }

  /** Build the turn's authoritative answer: the FINAL model response's
   * sanitized fragment accumulation (withheld fragments never join;
   * interim responses are replaced by the final one - 14.6), or the
   * raw completion when the final response did not stream (the
   * invoke fallback - the fragment path also emitted it as the single
   * final delta, so wire and record agree). */
  private answerOf(pending: PendingInteraction, completion: string): string {
    if (pending.sanitizedAnswer.length > 0) {
      return pending.sanitizedAnswer;
    }
    return completion;
  }

  /** The capture/redaction pipeline before the output callback (14.6:
   * no raw provider reasoning/tool fragments reach clients; uncertain
   * fragments are withheld; the complete record is authoritative). */
  private async emitFragment(
    pending: PendingInteraction,
    fragment: string,
  ): Promise<void> {
    if (pending.settled) return; // fenced after settlement
    if (pending.abort.signal.aborted) return; // fenced after abort
    if (this.stoppedReason !== null) return;
    if (!fragmentMayEmit(fragment)) {
      this.log.warn(
        "interaction fragment withheld: the fragment failed content inspection",
      );
      return;
    }
    if (Buffer.byteLength(fragment) > this.policy.maxOutputBytes) {
      return;
    }
    // The sanitized accumulation: the authoritative answer carries
    // exactly the FINAL response's fragments that passed inspection
    // (the withheld ones never join; an interim response's fragments
    // are replaced by the final answer - 14.6: the complete record
    // replaces the provisional stream).
    pending.sanitizedAnswer += fragment;
    const payload: ExecutionInteractionDeltaPayload = {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      interactionId: pending.payload.interactionId,
      index: pending.nextDeltaIndex,
      text: fragment,
    };
    pending.nextDeltaIndex += 1;
    try {
      await this.emitter.sendDelta(payload);
    } catch (err) {
      // Deltas are ephemeral (22.3): a lost delta is tolerated - the
      // complete outcome replaces provisional answers.
      this.log.warn(
        "interaction delta dropped:",
        err instanceof Error ? err.message : String(err),
      );
    }
  }

  /** Aggregate one turn's usage (22.6: partial known usage reported;
   * never estimated; never zero-on-unknown). */
  private noteUsage(
    pending: PendingInteraction,
    usage:
      | { promptTokens?: number; completionTokens?: number; totalTokens?: number }
      | undefined,
  ): void {
    if (!usage) return;
    const prompt = usage.promptTokens;
    const completion = usage.completionTokens;
    if (typeof prompt === "number" && Number.isInteger(prompt) && prompt >= 0) {
      pending.usagePrompt = (pending.usagePrompt ?? 0) + prompt;
      pending.anyUsageReported = true;
    }
    if (typeof completion === "number" && Number.isInteger(completion) && completion >= 0) {
      pending.usageCompletion = (pending.usageCompletion ?? 0) + completion;
      pending.anyUsageReported = true;
    }
  }

  private interactionUsageOf(pending: PendingInteraction): {
    usage: ExecutionInteractionCompletePayload["usage"];
    usageStatus: "KNOWN" | "UNKNOWN";
  } {
    if (!pending.anyUsageReported) {
      return { usage: null, usageStatus: "UNKNOWN" };
    }
    return {
      usage: {
        inputTokens: pending.usagePrompt,
        outputTokens: pending.usageCompletion,
        modelId: null,
      },
      usageStatus: "KNOWN",
    };
  }

  /** Publish ONE outcome: outbox persist BEFORE the wire publish (22.3). */
  private async publishOutcome(outcome: SettledOutcome): Promise<void> {
    if ("answer" in outcome) {
      await this.outbox.persistInteractionComplete(outcome);
      await this.emitter.sendComplete(outcome);
    } else {
      await this.outbox.persistInteractionFailed(outcome);
      await this.emitter.sendFailed(outcome);
    }
  }

  private async completeInteraction(
    pending: PendingInteraction,
    outcome: { kind: "complete"; answer: string },
  ): Promise<void> {
    if (pending.settled) return;
    pending.settled = true;
    pending.deadlineTimerGeneration += 1; // fence the deadline timer
    const usage = this.interactionUsageOf(pending);
    const payload: ExecutionInteractionCompletePayload = {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      interactionId: pending.payload.interactionId,
      ordinal: pending.payload.ordinal,
      answer: { text: outcome.answer },
      usage: usage.usage,
      usageStatus: usage.usageStatus,
      controlRequestIds: [...pending.controlRequestIds],
      completedAt: new Date(this.clock.now()).toISOString(),
    };
    pending.markSettled(payload);
    await this.publishOutcomeDurable(payload);
    this.afterSettle(pending, payload);
    this.onUsageSettled?.({
      interactionId: payload.interactionId,
      usage: payload.usage,
      usageStatus: payload.usageStatus,
    });
  }

  /** Fail ONE interaction: persist BEFORE publish; the interaction
   * failure NEVER fails the attempt (22.6) - it only settles the slot. */
  private async failInteraction(
    pending: PendingInteraction,
    errorCode: InteractionErrorCode,
    message: string,
  ): Promise<void> {
    if (pending.settled) return;
    pending.settled = true;
    pending.deadlineTimerGeneration += 1;
    // Fence the provider call (a late result must not re-run tools).
    pending.abort.abort();
    const usage = this.interactionUsageOf(pending);
    const payload: ExecutionInteractionFailedPayload = {
      executionId: this.executionId,
      dispatchId: this.dispatchId,
      interactionId: pending.payload.interactionId,
      ordinal: pending.payload.ordinal,
      error: {
        errorCode,
        message: message.slice(0, 2000),
        retryable: false,
      },
      usage: usage.usage,
      usageStatus: usage.usageStatus,
      controlRequestIds: [...pending.controlRequestIds],
      completedAt: new Date(this.clock.now()).toISOString(),
    };
    pending.markSettled(payload);
    await this.publishOutcomeDurable(payload);
    this.afterSettle(pending, payload);
    this.onUsageSettled?.({
      interactionId: payload.interactionId,
      usage: payload.usage,
      usageStatus: payload.usageStatus,
    });
  }

  /** The durable publish used by the settlement paths (identical to
   * publishOutcome; kept separate for clarity of the FIRST-settle rule
   * versus the replay re-publish). */
  private async publishOutcomeDurable(outcome: SettledOutcome): Promise<void> {
    await this.publishOutcome(outcome);
  }

  /** Post-settlement bookkeeping: replay map + marked history + slot
   * release (BOTH slots: the controller's pending pointer and the
   * coordinator's interaction slot — 22.5: settlement on HELD arms a
   * NEW FULL idle interval through endInteraction). */
  private afterSettle(
    pending: PendingInteraction,
    outcome: SettledOutcome,
  ): void {
    this.replay.set(pending.payload.interactionId, outcome);
    this.fingerprints.set(pending.payload.interactionId, pending.fingerprint);
    // 14.6: the history carries completed replies; a failed/partial
    // response is marked a failure, never successful instructions.
    this.history.push({ role: "user", content: pending.payload.message.text });
    this.history.push({
      role: "assistant",
      content:
        "answer" in outcome
          ? outcome.answer.text
          : "[failed response: " + outcome.error.errorCode + "]",
    });
    this.truncateHistory();
    // Release the coordinator's ONE interaction slot (the idle clock
    // re-arms on HELD through endInteraction; idempotent on a slot the
    // coordinator no longer owns).
    this.control.endInteraction(pending.payload.interactionId);
    if (this.pending === pending) {
      this.pending = null;
    }
  }

  /** Bounded history (policy.maxHistoryBytes): drop the OLDEST
   * user/assistant PAIRS first; never the current question (the current
   * question is pushed only after the turn settles - truncation happens
   * between turns). */
  private truncateHistory(): void {
    let bytes = historyBytes(this.history);
    while (bytes > this.policy.maxHistoryBytes && this.history.length >= 2) {
      this.history.shift();
      this.history.shift();
      bytes = historyBytes(this.history);
    }
    if (bytes > this.policy.maxHistoryBytes && this.history.length >= 2) {
      // Degenerate (one oversized pair): keep the newest pair only.
      this.history = this.history.slice(-2);
    }
  }
}

function historyBytes(
  history: Array<{ role: string; content: string }>,
): number {
  let total = 0;
  for (const message of history) {
    total += Buffer.byteLength(message.role) + Buffer.byteLength(message.content);
  }
  return total;
}