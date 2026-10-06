// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * OrchestrationExecutor (unified session execution design, D1): the
 * Agent-side handler for exactly ONE orchestration dispatch, in-Agent and
 * per session.
 *
 * Symmetry: InferenceExecutor : CONVERSATION = OrchestrationExecutor :
 * ORCHESTRATOR-step. The runner is the pure in-process orchestration
 * loop (no wire, unchanged); this executor is the session-bound driver
 * that owns admission, engine communication, the task-scoped workspace,
 * and the runner's lifecycle.
 *
 * Every frame is untrusted data: the assignment is validated with the
 * strict runtime schema before any workspace or model call. On a valid
 * dispatch the executor (protocol 9 order - load-bearing):
 *
 *   1. records the durable admission keyed on dispatchId, then emits
 *      `execution.accept` echoing dispatchId + assignmentDigest (the
 *      protocol 8.2 ORCHESTRATION variant);
 *   2. acquires the task-scoped checkout
 *      `<workspaceRoot>/tasks/<dispatchId>/checkout` (protocol 3/9:
 *      one workspace per task attempt, no registry, no lease, no
 *      generation - the checkout never crosses sessions);
 *   3. runs the step through the real OrchestrationRunner with the real
 *      file/command tools, the checkpoint service, and the durable
 *      outbox-backed event sink;
 *   4. on the outcome, pushes the attempt's commit to the run's target
 *      branch BEFORE reporting the terminal (D10 part 1: a push
 *      failure on COMPLETE/PAUSED converts to a retryable
 *      SOURCE_PUSH_FAILED execution.failed);
 *   5. releases the checkout on EVERY terminal path (finally), then the
 *      terminal frame is on the wire (release-before-terminal).
 *
 * The wire speaks the unified frame family only (D3): progress rides
 * execution.event with the protocol 8.4 types, terminals ride
 * execution.complete/failed/paused with the 8.5/8.6 shapes, HITL rides
 * execution.approval.requested then execution.paused.
 */
import { createHash, randomUUID } from "node:crypto";
import { readFileSync, rmSync } from "node:fs";
import path from "node:path";
import {
  OrchestrationRunner,
  HelperInvoker,
  GitWorkspaceManager,
  GitWorkspaceScope,
  GitWorkspaceInspector,
  GitCheckpointService,
  orchestrationAssignmentSchema,
} from "../orchestration/index.js";
import type {
  OrchestrationAssignment,
  OrchestrationRunResult,
  DispatchIdentity,
} from "../orchestration/types.js";
import { TargetBranchPusher } from "../orchestration/TargetBranchPusher.js";
import { ApprovalPolicyEvaluator } from "../orchestration/ApprovalPolicyEvaluator.js";
import { LocalContinuationStateStore } from "../orchestration/ContinuationStateStore.js";
import { InMemoryBudgetController } from "../orchestration/BudgetController.js";
import type {
  ExecutionSnapshotBudget,
} from "../interaction/ExecutionSnapshot.js";
import { ExecutionSnapshotPublisher } from "../interaction/ExecutionSnapshot.js";
import { FsOutboxBackend, OrchestrationOutbox } from "../protocol/OrchestrationOutbox.js";
import { AgentProtocolOrchestrationEventSink } from "../protocol/AgentProtocolOrchestrationEventSink.js";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { ExecutionFrameSender } from "../executor/ExecutionFrameSender.js";
import type { SessionRegistry } from "../session/SessionRegistry.js";
import { ExecutionControlCoordinator } from "../interaction/ExecutionControlCoordinator.js";
import { AttemptModelScheduler } from "../interaction/AttemptModelScheduler.js";
import { InteractiveController } from "../interaction/InteractiveController.js";
import type {
  ExecutionInteractionPayload,
  ExecutionControlPayload,
  ExecutionControlRequestResolvedPayload,
  ExecutionControlStatePayload,
  InteractionPolicy,
} from "../protocol/unifiedFrames.js";
import { SafeExecutionEventRing } from "../interaction/SafeExecutionEvent.js";
import type {
  ExecutionAcceptPayload,
  ExecutionCompletePayload,
  ExecutionFailedPayload,
  ExecutionPausedPayload,
  ExecutionCancelledPayload,
  ExecutionApprovalRequestedPayload,
  ExecutionInteractionDeltaPayload,
  ExecutionInteractionCompletePayload,
  ExecutionInteractionFailedPayload,
  ExecutionControlRequestPayload,
  SessionPolicy,
  CapturePolicy,
} from "../protocol/unifiedFrames.js";
import type { Logger } from "../models/index.js";

/**
 * The section 22.2 interaction-policy defaults the composition falls back
 * to when a session.open carried no interaction block (fail-safe: a
 * missing policy must never crash the dispatch). Bounds mirror the
 * engine's InteractionProperties defaults - idle 300 / response 120 /
 * input 16384 / output 65536 / iterations 8 / history 262144 / retention
 * 30 / USER_CHAT_ONLY.
 */
export const DEFAULT_INTERACTION_POLICY: InteractionPolicy = {
  version: 1,
  enabled: true,
  idleResumeAfterSeconds: 300,
  responseTimeoutSeconds: 120,
  maxInputBytes: 16384,
  maxOutputBytes: 65536,
  maxModelIterations: 8,
  maxHistoryBytes: 262144,
  transcriptRetentionDays: 30,
  contentMode: "USER_CHAT_ONLY",
};

export interface OrchestrationExecutorOptions {
  /** Where task-scoped workspaces live: <root>/tasks/<dispatchId>/checkout. */
  workspaceRoot: string;
  /** Where the durable outbox records live (agent-local). */
  outboxRoot: string;
  /** The stub/real model factory (same seam as ordinary inference). */
  chatModelFactory: ChatModelFactory;
  /** The Agent's typed execution-frame sender (the only emission seam). */
  sender: ExecutionFrameSender;
  /** The session registry - dispatch lookups + policy/capture resolution. */
  sessions: SessionRegistry;
  /** HITL: the project's autoHitlOnDestructive matrix input - captured
   * from the orchestration session.open (default true: suspend-on-
   * destructive is the conservative floor). */
  autoHitlOnDestructive?: boolean;
  logger?: Logger;
  /** Test seam: override the target-branch pusher (stub pusher). */
  pusher?: { push(options: {
    checkoutPath: string;
    targetBranch: string;
    accessToken?: string;
  }): Promise<void> };
  /** Test seam: override the workspace manager (stub acquire). */
  workspaceManager?: {
    acquire(source: {
      repoUrl: string;
      sourceBranch: string;
      sourceBaseCommit: string;
      targetBranch: string;
      accessToken: string;
    }): Promise<{
      checkoutPath: string;
      sourceBranch: string;
      targetBranch: string;
      baseCommit: string;
      workspaceId: string;
      generation: number;
    }>;
  };
}

/** One durable admission: dispatchId -> the accepted digest. */
interface Admission {
  assignmentDigest: string;
  admittedAt: string;
}

/** The start payload the executor receives (protocol 8.1 variant). */
export interface OrchestrationStartPayload {
  executionId: string;
  sessionId: string;
  dispatchId: string;
  attemptId: string;
  assignmentDigest: string;
  deadline?: string;
}

export class OrchestrationExecutor {
  private readonly options: OrchestrationExecutorOptions;
  private readonly outbox: OrchestrationOutbox;
  private readonly sink: AgentProtocolOrchestrationEventSink;
  private readonly pusher: TargetBranchPusher;
  private readonly log: Logger;
  /** Durable admission map keyed on dispatchId (not requestId). */
  private readonly admissions = new Map<string, Admission>();
  /**
   * The executionIds whose admissions are LIVE (recorded at
   * handleStart; bounded - a terminal dispatch's id stays until the
   * map's cap). The interaction arms' identity check consults this
   * when no runtime matches (an admitted-then-finished dispatch's
   * interaction settles as a durable failure, not a protocol error).
   */
  private readonly recentExecutionIds = new Set<string>();
  private static readonly RECENT_EXECUTION_ID_CAP = 64;
  /** Per-dispatch live state (run + cancellation signal + executionId + the
   * owning sessionId — §22.8 (D7) fatal session loss cancels by session). */
  private readonly dispatches = new Map<
    string,
    {
      executionId: string;
      sessionId?: string;
      cancellation: { cancelled: boolean };
    }
  >();
  /**
   * The session's host-enforced execution policy, keyed by the session the
   * orchestration dispatch rides (captured at session.open by the Agent).
   */
  private readonly sessionPolicies = new Map<string, SessionPolicy>();
  /**
   * The session's capture policy, keyed the same way - the sink the
   * dispatch executes through filters its progress stream with it
   * (null/absent means METADATA, fail closed).
   */
  private readonly sessionCapturePolicies = new Map<string, CapturePolicy>();
  /**
   * The live tighten-only allowance per dispatch - mutated by
   * execution.policy.update frames while the dispatch executes; the
   * runner reads through this object at every helper-call boundary.
   */
  private readonly allowanceSources = new Map<
    string,
    { maxTokens: number | null }
  >();
  /** The policy bound to each admitted dispatch. */
  private readonly dispatchPolicies = new Map<string, SessionPolicy>();
  /**
   * The section 22.2 interaction policy bound to each admitted dispatch
   * (from the session.open `interaction` block; the DEFAULT_INTERACTION_
   * POLICY covers an absent block - fail-safe composition).
   */
  private readonly sessionInteractionPolicies = new Map<
    string,
    InteractionPolicy
  >();
  /**
   * The live section 22 interaction runtime per dispatch (Task 7): the
   * ONE coordinator + controller + scheduler trio composed at
   * executeDispatch, keyed by dispatchId. handleInteraction /
   * settleControlRequestResolved resolve the executor here; the
   * executeDispatch teardown stops the trio and deletes the entry.
   */
  private readonly interactionRuntimes = new Map<
    string,
    {
      executionId: string;
      dispatchId: string;
      coordinator: ExecutionControlCoordinator;
      controller: InteractiveController;
      /**
       * In-flight interaction settlements keyed by interactionId (Task 8
       * §22.8 settle-before-terminal): the controller's stop() publishes
       * the pending interaction's outcome through the recorded handle()
       * settlement promise, so the executor's terminal path awaits these
       * to guarantee the durable outcome record reaches the wire BEFORE
       * the terminal frame. Entries are added by handleInteraction and
       * removed when the settlement resolves on a non-stop path.
       */
      settlements: Map<string, Promise<void>>;
    }
  >();
  /**
   * One dispatch executes at a time - a single agent coordinator owns the
   * runner state machine, and the task-scoped checkout admits exactly one
   * mutation chain. A second dispatch WAITS for the first to finish.
   */
  private executionChain: Promise<unknown> = Promise.resolve();

  constructor(options: OrchestrationExecutorOptions) {
    this.options = options;
    this.log = options.logger ?? console;
    this.pusher = new TargetBranchPusher();
    this.outbox = new OrchestrationOutbox({
      backend: new FsOutboxBackend(options.outboxRoot),
      send: async (frame, record) => {
        // The sink-outbox bridge: stamp the frame with the record's
        // messageId (recorded BEFORE first send) and reuse the record's
        // envelope sequence on retransmits, then post it through the
        // typed sender seam.
        await this.postOutboxFrame(frame.type, frame.payload, record);
        return true;
      },
    });
    this.sink = new AgentProtocolOrchestrationEventSink({ outbox: this.outbox });
  }

  /**
   * Handle one `execution.start` carrying the orchestration variant
   * (dispatchId + assignmentDigest). Returns true when the dispatch was
   * admitted (acceptance sent); false when the frame was rejected
   * (malformed or conflicting digest - fail closed).
   */
  async handleStart(payload: OrchestrationStartPayload): Promise<boolean> {
    const { executionId, dispatchId, sessionId } = payload;
    const digest = payload.assignmentDigest ?? "";
    if (!dispatchId || !digest || !executionId) {
      this.log.warn("orchestration start missing dispatchId/digest/executionId");
      return false;
    }

    // Idempotent replay: same digest re-accepts (the engine retransmits
    // the start until the accept crosses the wire - protocol 12.1);
    // conflicting bytes fail closed.
    const existing = this.admissions.get(dispatchId);
    if (existing) {
      if (existing.assignmentDigest === digest) {
        await this.sendAccept(executionId, dispatchId, digest);
        return true;
      }
      this.log.warn(
        `dispatch ${dispatchId} re-delivered with CONFLICTING digest - failing closed`,
      );
      return false;
    }

    // The assignment rides session.open (protocol 7.3) and was installed
    // by the SessionRegistry at open; the start frame only activates it.
    const session = this.options.sessions.get(sessionId);
    if (!session || !session.orchestration) {
      this.log.warn(
        `orchestration start for session ${sessionId} with no installed assignment - failing closed`,
      );
      return false;
    }
    const parsed = orchestrationAssignmentSchema.safeParse(session.orchestration);
    if (!parsed.success) {
      this.log.warn(
        `installed orchestration assignment failed schema validation: ${parsed.error.issues
          .map((i) => `${i.path.join(".")}: ${i.message}`)
          .join("; ")
          .slice(0, 500)}`,
      );
      return false;
    }
    const assignment = parsed.data as OrchestrationAssignment;

    // Durable admission BEFORE the acceptance crosses the wire. The
    // executionId joins the bounded recent set (the interaction arms'
    // identity fallback consults it once the runtime is gone).
    this.admissions.set(dispatchId, {
      assignmentDigest: digest,
      admittedAt: new Date().toISOString(),
    });
    if (!this.recentExecutionIds.has(executionId)) {
      if (this.recentExecutionIds.size >= OrchestrationExecutor.RECENT_EXECUTION_ID_CAP) {
        const oldest = this.recentExecutionIds.values().next().value;
        if (oldest !== undefined) this.recentExecutionIds.delete(oldest);
      }
      this.recentExecutionIds.add(executionId);
    }

    // Bind the session's policy to THIS dispatch so the runner enforces
    // the host limits; seed the allowance overlay the policy updates
    // tighten.
    const sessionPolicy = this.sessionPolicies.get(sessionId);
    const allowanceSource: { maxTokens: number | null } = { maxTokens: null };
    this.allowanceSources.set(dispatchId, allowanceSource);
    if (sessionPolicy) {
      this.dispatchPolicies.set(dispatchId, sessionPolicy);
    }

    // Acknowledge: the exact admitted digest (protocol 8.2 variant).
    await this.sendAccept(executionId, dispatchId, digest);

    // Execute the dispatch (async - the Agent keeps serving frames),
    // serialized behind any in-flight dispatch (one at a time). A dispatch
    // that throws before reporting its own terminal is converted here:
    // the engine must ALWAYS learn the outcome (never silent).
    const previous = this.executionChain;
    this.executionChain = (async () => {
      await previous.catch(() => undefined);
      try {
        await this.executeDispatch(assignment, executionId, dispatchId, sessionId);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        this.log.error("orchestration dispatch failed:", message);
        try {
          await this.emitFailedTerminal(executionId, null, {
            schemaVersion: "1.0",
            resultId: `crash-${dispatchId}`,
            resultDigest: createHash("sha256")
              .update(`${dispatchId}:crash`)
              .digest("hex"),
            dispatch: assignment.dispatch,
            status: "FAILED",
            retryDisposition: "RETRYABLE",
            summary: `dispatch crashed before reporting a terminal: ${message}`.slice(0, 4000),
            helperCalls: [],
            verifierResults: [],
            commandExecutions: [],
            changedFiles: [],
            commits: [],
            cleanWorktree: false,
            usage: { helperCalls: 0, rejectionCount: 0, totalTokens: 0 },
          }, assignment);
        } catch (emitErr) {
          this.log.error(
            "crash-terminal emission also failed:",
            emitErr instanceof Error ? emitErr.message : String(emitErr),
          );
        }
      }
    })();
    return true;
  }

  /**
   * Handle `execution.cancel` for an active orchestration dispatch. Keys
   * on the cancel payload's dispatchId (the engine's cancel payload
   * carries dispatchId for orchestration executions), falling back to
   * executionId. §22.8 (D7): a session-keyed cancel (sessionId passed as
   * executionId with dispatchId null when no dispatch matches) stops every
   * dispatch of that session - the fatal channel-loss path.
   */
  handleCancel(payload: {
    dispatchId?: string | null;
    executionId?: string | null;
  }): void {
    const key = payload.dispatchId ?? payload.executionId;
    if (!key) return;
    // Try the dispatch map first; the fallback keys the cancel by
    // executionId (recorded with the live dispatch state).
    let state = this.dispatches.get(key);
    if (!state) {
      for (const [, s] of this.dispatches) {
        if (s.executionId === key) {
          state = s;
          break;
        }
      }
    }
    if (state) {
      state.cancellation.cancelled = true;
      this.log.info(`orchestration dispatch ${key} cancellation signalled`);
      // Task 7 (14.2): a cancelled dispatch tears its interaction runtime
      // down too - the controller fails the pending interaction
      // (EXECUTION_CANCELLED) and rejects proposal waits; the
      // coordinator wakes parked gate waiters. Idempotent with the
      // executeDispatch finally (which deletes the entry).
      const runtime = this.interactionRuntimes.get(key);
      if (runtime) {
        try {
          void runtime.controller.stop("CANCEL").catch(() => undefined);
        } catch {
          // never out of a cancel path
        }
        runtime.coordinator.stop("CANCEL");
      }
      return;
    }
    // §22.8 (D7): no dispatch keyed by the id - treat it as a session id
    // and stop every dispatch of that session (no-op when none match).
    let sessionHits = 0;
    if (payload.dispatchId == null) {
      for (const [dispatchId, s] of this.dispatches) {
        if (s.sessionId === key) {
          s.cancellation.cancelled = true;
          sessionHits += 1;
          this.log.info(
            `orchestration dispatch ${dispatchId} cancellation signalled (session fatal, §22.8 D7)`,
          );
          // Task 7: the session-fatal path tears the dispatch's
          // interaction runtime down too (CHANNEL_LOST - the session is
          // dead; nothing admits afterward).
          const runtime = this.interactionRuntimes.get(dispatchId);
          if (runtime) {
            try {
              void runtime.controller.stop("CHANNEL_LOST").catch(() => undefined);
            } catch {
              // never out of the fatal path
            }
            runtime.coordinator.stop("CHANNEL_LOST");
          }
        }
      }
    }
    if (sessionHits === 0 && payload.dispatchId == null) {
      this.log.info(
        `orchestration cancel for ${key} matched no active dispatch (no-op)`,
      );
    }
  }

  /**
   * Record the session.open policy block so the orchestration dispatch
   * it serves enforces the host limits.
   */
  recordSessionPolicy(
    sessionId: string,
    policy: SessionPolicy | null | undefined,
  ): void {
    if (policy) {
      this.sessionPolicies.set(sessionId, policy);
    }
  }

  /**
   * Record the session.open capture block so the orchestration sink's
   * progress stream respects it.
   */
  recordSessionCapture(
    sessionId: string,
    capture: CapturePolicy | null | undefined,
  ): void {
    if (capture) {
      this.sessionCapturePolicies.set(sessionId, capture);
    }
  }

  /**
   * Record the session.open section 22.2 interaction block so the
   * dispatch's interaction runtime composes with the ENGINE-PUBLISHED
   * policy (not a guess). Absent block: the composition fails safe to
   * DEFAULT_INTERACTION_POLICY (no crash, honest bounds).
   */
  recordSessionInteraction(
    sessionId: string,
    policy: InteractionPolicy | null | undefined,
  ): void {
    if (policy) {
      this.sessionInteractionPolicies.set(sessionId, policy);
    }
  }

  // -- protocol 22.3/22.6/22.7 inbound arms (Task 7) -----------------

  /**
   * The worker's `execution.interaction` arm. Resolves the live dispatch
   * BY executionId AND dispatchId (both must match the recorded runtime);
   * a mismatch is the caller's protocol.error IDENTITY_MISMATCH duty.
   * Resolved: forward into the InteractiveController's bounded turn
   * (the controller owns replay/conflict/terminal outcomes internally).
   */
  async handleInteraction(
    payload: ExecutionInteractionPayload,
  ): Promise<"handled" | "unknown-dispatch" | "identity-mismatch"> {
    const runtime = this.interactionRuntimes.get(payload.dispatchId ?? "");
    if (!runtime) {
      // Fail-safe no-live-dispatch path: the identity may still belong to
      // a KNOWN orchestration session (the assignment rides session.open).
      // The settled answer is a DURABLE interaction.failed family
      // outcome (the admission gate owns admission - a dispatch that
      // never started serves no chat), never a protocol.error: the
      // engine's re-drive/retry machinery reads the outcome.
      if (this.identityBelongsToSession(payload)) {
        await this.emitInteractionFailedDurable(payload, {
          errorCode: "EXECUTION_TERMINAL",
          message:
            "no live dispatch owns this interaction" +
            " (the execution is not running; the interaction cannot run)",
        });
        return "handled";
      }
      return "unknown-dispatch";
    }
    if (payload.executionId !== runtime.executionId) {
      return "identity-mismatch";
    }
    // Task 8 (§22.8 settle-before-terminal): the in-flight settlement is
    // recorded BEFORE it is awaited so the executor's terminal path can
    // await the durable outcome publish - the controller's stop()
    // settles the pending interaction by resolving the settlement
    // deferred, and the outcome's wire publish completes inside THIS
    // promise.
    const settlement = runtime.controller.handle(payload).catch(() => undefined);
    runtime.settlements.set(payload.interactionId, settlement);
    void settlement.then(() => runtime.settlements.delete(payload.interactionId));
    await settlement;
    return "handled";
  }

  /**
   * Whether the interaction identity resolves through the SESSIONS: the
   * dispatchId matches an installed orchestration assignment AND the
   * executionId matches the recorded admission (or the session's own
   * orchestration session id when no admission recorded it yet).
   */
  private identityBelongsToSession(
    payload: ExecutionInteractionPayload,
  ): boolean {
    const dispatchId = payload.dispatchId ?? "";
    if (!dispatchId) return false;
    // The admissions map keys the digest per dispatchId (durable).
    const admitted = this.admissions.get(dispatchId);
    if (!admitted) return false;
    // A LIVE dispatch state (recorded at execution start) binds the
    // executionId exactly; an already-finished dispatch's runtime is
    // gone, so the admission alone accepts (the outcome is a settled
    // failure either way - a re-drive after terminal replays it).
    const live = this.dispatches.get(dispatchId);
    if (live) return live.executionId === payload.executionId;
    return this.recentExecutionIds.has(payload.executionId);
  }

  /**
   * The worker's `execution.control` arm (§22.4, Task 11 fix): route ONE
   * engine HOLD/CONTINUE command to the dispatch's coordinator. The
   * coordinator owns the entire disposition (fresh apply, identical-bytes
   * replay, STALE_CONTROL_REVISION / CONTROL_REVISION_CONFLICT
   * rejections, terminal refusal) and its durable state chain — this arm
   * only resolves the identity. An unknown dispatch is the caller's
   * protocol.error IDENTITY_MISMATCH duty; a known identity is handled
   * (the coordinator's outcome is its own durable frames).
   */
  async handleControlCommand(
    payload: ExecutionControlPayload,
  ): Promise<"handled" | "unknown-dispatch" | "identity-mismatch"> {
    const runtime = this.interactionRuntimes.get(payload.dispatchId ?? "");
    if (!runtime) {
      return "unknown-dispatch";
    }
    if (payload.executionId !== runtime.executionId) {
      return "identity-mismatch";
    }
    // The engine's command frame carries its own wire messageId (the
    // outbox envelope's id) — the coordinator's replay fingerprint keys
    // on it (22.4: an identical command replay returns the stored
    // disposition without reapplying).
    await runtime.coordinator.apply(
      payload,
      `ctl-${payload.controlRevision}-${payload.action}`,
    );
    return "handled";
  }

  /**
   * The worker's `execution.control.request.resolved` arm - the
   * coordinator's inhibitor settle FIRST, then the controller's proposal
   * wait. Fire-and-forget (the settle is synchronous state). The same
   * double-identity rule applies (unknown/mismatched -> refuse).
   */
  settleControlRequestResolved(
    payload: ExecutionControlRequestResolvedPayload,
  ): "settled" | "unknown-dispatch" | "identity-mismatch" {
    const runtime = this.interactionRuntimes.get(payload.dispatchId ?? "");
    if (!runtime) {
      return "unknown-dispatch";
    }
    if (payload.executionId !== runtime.executionId) {
      return "identity-mismatch";
    }
    // The coordinator settles FIRST (the CONFIRMATION inhibitor rides the
    // 22.4 state machine; the controller's proposal wait resolves after).
    runtime.coordinator.resolveProposal(payload);
    runtime.controller.settleProposalResolution(payload);
    return "settled";
  }

  /** The interaction runtime for observability/tests (undefined = none).
   * Exposes the in-flight settlement map so tests can await a pending
   * interaction's durable outcome (the §22.8 settle-before-terminal
   * contract) without reaching into the private runtime record. */
  interactionRuntimeOf(
    dispatchId: string,
  ): {
    coordinator: ExecutionControlCoordinator;
    controller: InteractiveController;
    /** In-flight interaction settlements keyed by interactionId (Task 8). */
    settlements: Map<string, Promise<void>>;
  } | undefined {
    const runtime = this.interactionRuntimes.get(dispatchId);
    if (!runtime) return undefined;
    return {
      coordinator: runtime.coordinator,
      controller: runtime.controller,
      settlements: runtime.settlements,
    };
  }

  /**
   * Task 10 §22.8/§14.4: the connection-state arm for a SESSION (the
   * supervisor forwards `connection-state` across the worker bridge; the
   * worker routes every dispatch runtime of that session here).
   *
   *  - ready=false (control-socket drop / reconciling): fences every live
   *    runtime's idle timer (the coordinator retains the armed instant as
   *    an observation — timers stop, nothing auto-resumes while
   *    disconnected).
   *  - ready=true (authoritative KEEP): re-arms toward the SAME retained
   *    instant (§14.4) through coordinator.setConnectionReady(true) — the
   *    KEEP arm owns this emission; the client only forwards.
   *
   * Idempotent by state (the coordinator ignores same-value flips); a
   * session with no bound runtime is a no-op (nothing to fence/arm).
   */
  setSessionConnectionReady(sessionId: string, ready: boolean): void {
    for (const [dispatchId, runtime] of this.interactionRuntimes) {
      if (this.dispatches.get(dispatchId)?.sessionId !== sessionId) {
        continue;
      }
      runtime.coordinator.setConnectionReady(ready);
      this.log.debug(
        `interaction runtime ${dispatchId}: connectionReady=${ready} (session ${sessionId})`,
      );
    }
  }

  /** The capture policy bound to a dispatch's session (undefined = none). */
  private sessionCaptureOf(sessionId: string): CapturePolicy | undefined {
    return this.sessionCapturePolicies.get(sessionId);
  }

  /** The policy a dispatch's session carried (undefined = none captured). */
  private sessionPolicyOf(dispatchId: string): SessionPolicy | undefined {
    return this.dispatchPolicies.get(dispatchId);
  }

  /**
   * Apply an execution.policy.update to a live orchestration dispatch -
   * tighten-only. Returns the verdict so the caller can reject a
   * violation with protocol.error.
   */
  applyPolicyUpdate(payload: {
    dispatchId?: string | null;
    usage: { orchestrationFunctionCalls: number; totalTokens: number };
    allowance?: { maxTokens?: number | null } | null;
  }): "applied" | "rejected" | "unknown-dispatch" {
    const dispatchId = payload.dispatchId;
    if (!dispatchId) {
      return "unknown-dispatch";
    }
    const source = this.allowanceSources.get(dispatchId);
    if (!source) {
      return "unknown-dispatch";
    }
    const newLimit = payload.allowance?.maxTokens ?? null;
    if (newLimit !== null && source.maxTokens !== null && newLimit > source.maxTokens) {
      return "rejected";
    }
    // The engine's durable accounting is authoritative - the reconciled
    // ceiling applies at the next boundary. Lower engine totals than
    // local counters cannot roll the runner's accounting backward.
    source.maxTokens = newLimit;
    return "applied";
  }

  /**
   * The engine's protocol.ack for a host->engine durable frame: forward
   * the acknowledgedMessageId to the outbox seam (best-effort - an
   * unknown id is a no-op).
   */
  async handleEngineAck(acknowledgedMessageId: string): Promise<void> {
    try {
      await this.outbox.acknowledgeByMessageId(acknowledgedMessageId);
    } catch (err) {
      this.log.warn("outbox ack forward failed:", err);
    }
  }

  /** Retransmit unacknowledged outbox records (after reconnect). */
  async retransmit(): Promise<void> {
    try {
      await this.outbox.drain();
    } catch (err) {
      this.log.warn("outbox retransmission failed:", err);
    }
  }

  // -- internals --------------------------------------------------

  private async sendAccept(
    executionId: string,
    dispatchId: string,
    digest: string,
  ): Promise<void> {
    const accept: ExecutionAcceptPayload = {
      executionId: executionId as ExecutionAcceptPayload["executionId"],
      startedAt: new Date().toISOString(),
      // Protocol 8.2: resolvedModelId is present for INFERENCE only -
      // the ORCHESTRATION variant echoes the dispatch identity instead.
      // (zod infers the resolvedModelId field as string; the protocol 8.2
      // ORCHESTRATION variant sends the JSON null literal - recorded
      // deviation from the inferred schema, mirroring the Java record's
      // nullable handling.)
      resolvedModelId: "" as unknown as string,
      dispatchId: dispatchId as ExecutionAcceptPayload["dispatchId"],
      assignmentDigest: digest,
    };
    await this.options.sender.sendExecutionAccept(accept);
  }

  /** Run one admitted dispatch end to end. */
  private async executeDispatch(
    assignment: OrchestrationAssignment,
    executionId: string,
    dispatchId: string,
    sessionId: string,
  ): Promise<void> {
    const { dispatch, source, step } = assignment;
    const o = step.orchestration;
    const runId = dispatch.runId;

    // Bind the dispatch's session capture policy to the sink BEFORE any
    // side effect reports progress - the progress stream rides this
    // session's capture limits (null/absent means METADATA).
    this.sink.bindCapturePolicy(this.sessionCaptureOf(sessionId) ?? null);

    // The policy/allowance captured at admission (bound to this
    // dispatch) - the runner enforces the host limits and reads the live
    // tighten-only allowance at every helper-call boundary.
    const sessionPolicy = this.sessionPolicyOf(dispatchId);
    const allowanceSource =
      this.allowanceSources.get(dispatchId) ?? { maxTokens: null };

    this.log.info(
      `orchestration dispatch ${dispatchId} executing (step ${step.id}, run ${runId})`,
    );

    // Task-scoped workspace (protocol 3/9): each orchestrator attempt
    // acquires its own checkout keyed by dispatchId - the checkout never
    // crosses sessions and is released before the terminal/pause frame.
    // No registry, no lease, no generation.
    const manager =
      this.options.workspaceManager ?? new GitWorkspaceManager(this.options.workspaceRoot);
    const checkout = await manager.acquire(
      {
        repoUrl: source.repoUrl,
        sourceBranch: source.sourceBranch,
        sourceBaseCommit: source.sourceBaseCommit,
        targetBranch: source.targetBranch,
        // The credential vault seam: when the session delivered a
        // workspace credential envelope its ref resolves here; empty
        // string otherwise (as the previous behavior).
        accessToken: this.resolveSourceToken(sessionId, source),
      },
      undefined,
      { dispatchId },
    );
    this.log.info(
      `orchestration dispatch ${dispatchId} cloned ${source.sourceBaseCommit.slice(0, 12)} at ${checkout.checkoutPath}`,
    );

    const cancellation = { cancelled: false };
    this.dispatches.set(dispatchId, { executionId, sessionId, cancellation });
    // The safe-events provider (14.6): the sanitized metadata events the
    // execution publishes ride this bounded ring; the interaction tools
    // surface them read-only. Bound to the executor for the EXECUTING
    // dispatch (one dispatch at a time - the chain serialization makes
    // the active ring unambiguous); cleared in the finally.
    const recentEvents = new SafeExecutionEventRing();
    this.activeRecentEvents = recentEvents;
    // The composed runtime holders (null = composition failed - the
    // ungated fallback; the interaction arms stay closed). Hoisted so the
    // finally's teardown reaches them on EVERY terminal path.
    let composerCoordinator: ExecutionControlCoordinator | null = null;
    let composerController: InteractiveController | null = null;
    let attemptScheduler: AttemptModelScheduler | null = null;
    // §22.5 (Task 11 fix): the wall-clock deadline wake - the runner
    // enforces executionTimeoutSeconds at its helper-call boundary, but a
    // HELD-parked gate never re-runs that boundary; the coordinator's
    // markDeadlineExpired() is the §22.9 "HELD deadline expiry wakes gate"
    // seam Tasks 4/5 shipped WITHOUT a producer. This ONE-SHOT timer is
    // that producer: when the session policy carries a wall-clock timeout,
    // arm it at composition; expiry wakes parked waiters
    // (DEADLINE_EXPIRED - the runner maps the abort to its terminal path)
    // and fences idle/timers "even without user input" (§22.5). Cleared
    // in the finally on every terminal path (never fires after teardown).
    let deadlineWakeTimer: NodeJS.Timeout | null = null;

    try {
      const scope = new GitWorkspaceScope();
      const inspector = new GitWorkspaceInspector();
      const stepWorkspace = scope.resolve(checkout, o.sourceSubPath);

      // Trusted spec load: runner-owned read with digest.
      let specification:
        | { content: string; sha256: string; byteLength: number }
        | undefined;
      if (o.specPath) {
        const specAbs = path.join(checkout.checkoutPath, o.specPath);
        const content = readFileSync(specAbs, "utf-8");
        specification = {
          content,
          sha256: createHash("sha256").update(content).digest("hex"),
          byteLength: Buffer.byteLength(content),
        };
      }

      const checkpointService = new GitCheckpointService({
        checkoutPath: checkout.checkoutPath,
        targetBranch: checkout.targetBranch,
        sourceSubPath: o.sourceSubPath,
        runId,
        stepId: step.id,
        commitMessage: o.checkpointStrategy.commitMessage,
        allowNoChanges: o.checkpointStrategy.allowNoChanges,
      });

      // Protocol 8.4 progress events: ORCHESTRATION_FUNCTION_STARTED /
      // ORCHESTRATION_FUNCTION_COMPLETED with the 8.4 data keys (callId,
      // functionName, modelCode?, purpose / callId, outcome, usage,
      // workspaceRevision?). "Helper" stays the SDK class name; the
      // wire speaks the protocol types.
      const helperEventCounters = new Map<string, number>();
      const observingInvoker = new HelperInvoker({
        attemptOrdinal: dispatch.attemptOrdinal,
        chatModelFactory: this.options.chatModelFactory,
        turnExecutor: new TurnExecutor({}),
      });
      const originalInvoke = observingInvoker.invoke.bind(observingInvoker);
      observingInvoker.invoke = async (...args) => {
        const [assignmentArg, helperName, purpose] = args;
        const seq = (helperEventCounters.get(helperName) ?? 0) + 1;
        helperEventCounters.set(helperName, seq);
        await this.sink.emitEvent({
          dispatch,
          type: "ORCHESTRATION_FUNCTION_STARTED",
          callId: `call-${dispatchId}-${helperName}-${seq}`,
          functionName: helperName,
          purpose,
        });
        // The safe-events ring (14.6): the SAME boundary's sanitized
        // metadata (callId/functionName/purpose) - the interaction's
        // get_recent_events surface reads these.
        safeStarted(`call-${dispatchId}-${helperName}-${seq}`, helperName, purpose);
        const outcome = await originalInvoke(
          assignmentArg, helperName, purpose, args[3], args[4], args[5],
        );
        // Fix 5: the bounded snapshot at the helper-function completion
        // boundary (the existing ORCHESTRATION_FUNCTION_COMPLETED event
        // already marks it; the PROGRESS-class snapshot publishes the
        // cumulative identities + budget view alongside it).
        await snapshotPublisherLocal.publish();
        await this.sink.emitEvent({
          dispatch,
          type: "ORCHESTRATION_FUNCTION_COMPLETED",
          callId: outcome.helperCall.callId,
          outcome: outcome.helperCall.status,
          usage: {
            helperCalls: 1,
            rejectionCount: 0,
            totalTokens: outcome.tokenCount,
          },
        });
        safeCompleted(
          outcome.helperCall.callId,
          outcome.helperCall.status,
          { helperCalls: 1, totalTokens: outcome.tokenCount },
        );
        return outcome;
      };

      // Task 5 fix (§13 shared budget): ONE BudgetController per attempt,
      // built HERE (the executor's composition seam) and injected into
      // the runner run options so the runner uses the same instance (and
      // propagates it to helpers via its existing setBudget) instead of
      // constructing its own. The restore moves up to this construction:
      // a same-dispatchId restart (a crash's retry of the SAME dispatch)
      // seeds the counters from the continuation manifest so a crash
      // cannot reset a budget (§13); a decision-bearing HITL resume is a
      // new attempt (§17.2) and a new engine attempt (new dispatchId)
      // carries fresh counters — same semantics the ContinuationStateStore
      // documents for counter restoration.
      let restoredCounters: import("../orchestration/BudgetController.js").BudgetCounters | undefined;
      const continuationId = assignment.continuation?.continuationId;
      if (continuationId && !assignment.continuation?.decision) {
        const restoreStore = new LocalContinuationStateStore(
          path.join(this.options.outboxRoot, "continuations"),
        );
        const manifest = restoreStore.get(continuationId);
        // Same-dispatch restart only: the manifest's dispatchId must
        // match THIS dispatch (§13 — "restarting or replaying the same
        // dispatchId restores that dispatch's counters").
        if (manifest && manifest.dispatchId === dispatchId) {
          restoredCounters = manifest.budgetCounters;
        }
      }
      const budget = new InMemoryBudgetController(o.budget, restoredCounters);
      const budgetOf = (): ExecutionSnapshotBudget => ({
        budgetLimits: budget.limits(),
        budgetTotal: budget.counters(),
        // §22.6: the shared controller's counters are the authoritative
        // KNOWN totals for this attempt; UNKNOWN settlement cases arrive
        // with Task 8's usage-setlement wiring.
        usageStatus: "KNOWN" as const,
      });

      // ---- Task 7 composition (14.2-14.6 / 22.2-22.7): the interaction
      // runtime. ONE coordinator + ONE attempt scheduler + ONE embedded
      // controller per admitted dispatch, composed HERE (after the
      // budget's restore, before the publisher that reads the
      // coordinator). Fail-safe: a session without an interaction block
      // rides the 22.2 defaults; a composition failure must never crash
      // the dispatch - the runner falls back to the ungated loop and the
      // interaction arms stay closed (the runtime registry is empty).
      const interactionPolicy: InteractionPolicy =
        this.sessionInteractionPolicies.get(sessionId) ??
        DEFAULT_INTERACTION_POLICY;
      try {
        composerCoordinator = new ExecutionControlCoordinator({
          executionId,
          dispatchId,
          policy: interactionPolicy,
          clock: { now: () => Date.now() },
          scheduler: {
            schedule: (delayMs, callback) => {
              const timer = setTimeout(callback, delayMs);
              timer.unref?.();
            },
          },
          outbox: {
            persistControlState: (
              payload: ExecutionControlStatePayload,
              commandMessageId: string | null,
            ) => {
              // 22.4: stamp the command correlation onto the payload
              // reference - the coordinator publishes THE SAME object
              // after persist, so the live sender bridge reads it; the
              // outbox record ALSO carries it (retransmit path re-stamps
              // from the record via postOutboxFrame).
              (payload as ExecutionControlStatePayload & {
                correlationIdOverride?: string;
              }).correlationIdOverride = commandMessageId ?? undefined;
              return this.outbox.persistControlState(payload, commandMessageId);
            },
          },
          emitter: {
            // 22.4: the coordinator publishes through the typed sender
            // (the outbox record carries the record's own wire identity
            // through the sender bridge's control_state arm).
            publish: (payload: ExecutionControlStatePayload) => {
              void this.sendControlState(payload);
            },
          },
          logger: this.log,
        });
        // 14.5: ONE scheduler per attempt - the runner's gate loop and
        // the controller's interaction turns serialize on one permit
        // (the runner reuses THIS instance through the modelScheduler
        // run option; interaction takes priority at the next release).
        attemptScheduler = new AttemptModelScheduler({
          control: composerCoordinator,
        });
        // The controller's OWN model adapter: a SECOND factory
        // resolution with the SAME model config (never a shared
        // rebinding).
        const controllerModelDef = assignment.models.find(
          (m) => m.code === o.modelCode,
        );
        if (!controllerModelDef) {
          throw new Error(
            `orchestration model not in assignment: ${o.modelCode}`,
          );
        }
        const controllerModel = await this.options.chatModelFactory.resolve(
          controllerModelDef as unknown as Parameters<
            ChatModelFactory["resolve"]
          >[0],
          `interactive-${dispatchId}`,
        );
        composerController = new InteractiveController({
          executionId,
          dispatchId,
          interactionPolicy,
          control: composerCoordinator,
          modelScheduler: attemptScheduler,
          model: controllerModel,
          getSnapshot: () => snapshotPublisherLocal.current(),
          recentEvents: (limit: number) => recentEvents.recent(limit),
          budget,
          outbox: {
            persistInteractionComplete: (payload) =>
              this.outbox.persistInteractionComplete(payload),
            persistInteractionFailed: (payload) =>
              this.outbox.persistInteractionFailed(payload),
            persistControlRequest: (payload) =>
              this.outbox.persistControlRequest(payload),
          },
          emitter: {
            sendDelta: (payload) => this.sendInteractionDelta(payload),
            sendComplete: (payload) => this.sendInteractionComplete(payload),
            sendFailed: (payload) => this.sendInteractionFailed(payload),
            sendControlRequest: (payload) => this.sendControlRequest(payload),
          },
          // Task 8 (§22.8): a settled interaction's usage settles as a
          // USAGE_UPDATED event carrying the settlement identity block -
          // the executor owns the sink; the controller stays sink-blind.
          onUsageSettled: (settlement) => {
            void this.emitUsageSettlement(settlement, dispatch, executionId, dispatchId);
          },
          logger: this.log,
        });
        // Bind the runtime BEFORE the run starts: an interaction frame
        // for an admitted-but-not-yet-running dispatch resolves (the
        // controller handles replay/terminal/refusal internally - no
        // silent drop, no premature IDENTITY_MISMATCH).
        this.interactionRuntimes.set(dispatchId, {
          executionId,
          dispatchId,
          coordinator: composerCoordinator,
          controller: composerController,
          settlements: new Map<string, Promise<void>>(),
        });
        // Task 10 §22.8: the session-keyed crosswalk - the registry's
        // interactionRuntimeOf (and the resume report + the KEEP rearm)
        // resolve THIS runtime from the SESSION id; unbound at teardown.
        this.options.sessions.bindInteractionCoordinator(sessionId, {
          dispatchId,
          executionId,
          coordinator: composerCoordinator,
        });
        // §22.5 (Task 11 fix): arm the deadline wake ONLY when a runtime
        // was composed (the coordinator owns the wake; an ungated
        // fallback dispatch keeps the runner's own boundary check as its
        // only deadline enforcement - unchanged behavior).
        if (sessionPolicy?.executionTimeoutSeconds != null) {
          deadlineWakeTimer = setTimeout(
            () => {
              try {
                composerCoordinator?.markDeadlineExpired();
                this.log.info(
                  `orchestration dispatch ${dispatchId} wall-clock deadline expired - hold gate woken (§22.5)`,
                );
              } catch (wakeErr) {
                this.log.warn(
                  `deadline wake failed for dispatch ${dispatchId}:`,
                  wakeErr instanceof Error ? wakeErr.message : String(wakeErr),
                );
              }
            },
            sessionPolicy.executionTimeoutSeconds * 1000,
          );
          deadlineWakeTimer.unref?.();
        }
      } catch (compositionErr) {
        // Fail-safe: no interaction runtime - the dispatch runs exactly
        // as today (ungated); inbound interaction arms fail closed on
        // the missing runtime. Loud log, never a crash.
        this.log.error(
          `interaction runtime composition failed for dispatch ${dispatchId}: ` +
            (compositionErr instanceof Error
              ? compositionErr.message
              : String(compositionErr)),
        );
        composerCoordinator = null;
        attemptScheduler = null;
        composerController = null;
      }

      // The observing invoker feeds the ring at the helper boundaries:
      // STARTED/COMPLETED carry only sanitized metadata columns (callId,
      // functionName, outcome, usage - the CaptureFilter allowlist).
      const safeStarted = (
        callId: string,
        functionName: string,
        purpose?: string,
      ): void => {
        recentEvents.record({
          type: "ORCHESTRATION_FUNCTION_STARTED",
          at: new Date().toISOString(),
          data: { callId, functionName, ...(purpose ? { purpose } : {}) },
        });
      };
      const safeCompleted = (
        callId: string,
        outcome: string,
        usage: { helperCalls: number; totalTokens: number },
      ): void => {
        recentEvents.record({
          type: "ORCHESTRATION_FUNCTION_COMPLETED",
          at: new Date().toISOString(),
          data: { callId, outcome, usage },
        });
      };
      // Task 5 fix (§22.4/14.2): the bounded immutable snapshot publisher.
      // The hold overlay rides the COMPOSED coordinator: the snapshot's
      // holdState IS the coordinator's effectiveState when a runtime was
      // composed (honest gate evidence); RUNNING without one.
      const snapshotPublisherLocal = new ExecutionSnapshotPublisher({
        emit: async (event) => {
          // The ring records the SANITIZED metadata columns this event
          // carries (the same bytes the engine's execution.event sees).
          await this.recordSafeEvent(event);
          // The publisher's event already carries its own `dispatch`
          // identity block (from ids()); spread it as the whole event -
          // the sink's OrchestrationProgressEvent takes it as-is.
          await this.sink.emitEvent(event as Parameters<
            AgentProtocolOrchestrationEventSink["emitEvent"]
          >[0]);
        },
        ids: () => ({
          executionId,
          dispatchId,
          workflowId: dispatch.workflowId,
          runId: dispatch.runId,
          stepId: dispatch.stepId,
          taskId: dispatch.taskId,
          attemptId: dispatch.attemptId,
          attemptOrdinal: dispatch.attemptOrdinal,
          holdState: composerCoordinator
            ? composerCoordinator.snapshot().effectiveState
            : ("RUNNING" as const),
        }),
        budgetOf,
        helperCallsOf: () => budget.counters().helperCalls,
        rejectionsOf: () => budget.counters().rejectionCount,
      });

      const runner = new OrchestrationRunner({
        chatModelFactory: this.options.chatModelFactory,
        helperInvoker: observingInvoker,
        turnExecutor: new TurnExecutor({}),
        workspace: stepWorkspace,
        workspaceInspector: inspector,
        checkpointService,
        expectedHead: checkout.baseCommit,
        allowCheckpoint: assignment.policy.gitPolicy.allowCheckpoint,
        cancellation,
        ...(specification ? { specification } : {}),
        // HITL: the evaluator combines the pinned Profile's
        // approvalPolicy with the project's autoHitlOnDestructive matrix
        // input; the sink rides the durable outbox
        // (execution.approval.requested); the suspension publisher
        // persists the continuation under the outbox root (host-local);
        // the loader restores it for a decision-bearing continuation.
        approvalEvaluator: new ApprovalPolicyEvaluator({
          autoHitlOnDestructive: this.options.autoHitlOnDestructive ?? true,
          approvalTtlSeconds: assignment.policy.approvalRequestTtlSeconds,
        }),
        approvalSink: {
          request: async (r) => {
            await this.emitApprovalRequested(executionId, dispatchId, {
              approvalRequestId: r.approvalRequestId,
              action: r.action,
              snapshotTreeHash: r.snapshotTreeHash,
              stateDigest: r.stateDigest,
              expiresAt: r.expiresAt,
            });
          },
        },
        suspensionPublisher: {
          publish: async (input) => {
            // The manifest binds the FULL suspension identity - pending
            // action, approval request id, expiry - so the resume
            // validation restores exactly what the human approved.
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const full = store.put({
              continuationId: `cont-${input.dispatch.dispatchId}-hitl`,
              dispatchId: input.dispatch.dispatchId,
              attemptOrdinal: input.dispatch.attemptOrdinal,
              budgetCounters: {
                helperCalls: 0,
                totalTokens: 0,
                rejectionCount: 0,
              },
              completedCallIds: [],
              candidateTreeHash: input.candidateTreeHash,
              workspaceRevision: input.workspaceRevision,
              verifierHistory: [],
              pendingAction: {
                actionId: input.action.actionId,
                type: input.action.type,
                riskClass: input.action.riskClass,
                summary: input.action.summary,
                digest: input.action.digest,
              },
              approvalRequestId: input.approvalRequestId,
              suspensionExpiresAt: input.expiresAt,
              createdAt: new Date().toISOString(),
            });
            return {
              continuationId: full.continuationId,
              continuationRef: `local:${full.continuationId}`,
              snapshotTreeHash: full.candidateTreeHash,
              stateDigest: full.stateDigest,
            };
          },
        },
        suspensionLoader: {
          load: (continuationId) => {
            // Restore the stored suspension for the decision validation -
            // the manifest carries the full identity (pending action,
            // request id, expiry) the typed decision envelope binds
            // against.
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const manifest = store.get(continuationId);
            if (!manifest || !manifest.pendingAction || !manifest.approvalRequestId) {
              return null;
            }
            return {
              continuationId: manifest.continuationId,
              previousDispatchId: manifest.dispatchId,
              suspension: {
                continuationId: manifest.continuationId,
                continuationRef: `local:${manifest.continuationId}`,
                snapshotTreeHash: manifest.candidateTreeHash,
                workspaceRevision: manifest.workspaceRevision,
                stateDigest: manifest.stateDigest,
                reason: "HITL_APPROVAL" as const,
                approvalRequestId: manifest.approvalRequestId,
                pendingAction: manifest.pendingAction,
                expiresAt: manifest.suspensionExpiresAt,
              },
              manifest,
            };
          },
        },
        // The retry-continuation publication + restore - RETRYABLE safe
        // boundaries persist the manifest; the engine's retry dispatch
        // (decision-less continuation) restores completed-call identities
        // + verifier history here.
        retryContinuationPublisher: {
          publish: async (input) => {
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const full = store.put({
              continuationId: `cont-${input.dispatchId}-retry`,
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
            return {
              continuationId: full.continuationId,
              continuationRef: `local:${full.continuationId}`,
              stateDigest: full.stateDigest,
            };
          },
        },
        retryContinuationLoader: {
          load: (continuationId) => {
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            return store.get(continuationId);
          },
        },
        // Task 7: the composed hold gate - the runner's helper
        // admission (BEFORE_HELPER_CALL) and the final Git checkpoint
        // (BEFORE_GIT_EFFECT) park while HELD; the turn-level
        // boundaries ride the SAME attempt scheduler.
        ...(composerCoordinator ? { toolGate: composerCoordinator } : {}),
      });

      // Task 5 fixes 1+5 (§13 shared budget + §22.4 snapshots): inject
      // the executor-composed controller into the runner (single shared
      // instance; the runner's own construction is bypassed and its
      // existing setBudget propagates the SAME instance to helpers) and
      // share it with the observing invoker so helper turns' TurnRun
      // budget is the same controller even before the runner wires it.
      observingInvoker.setBudget(budget);

      const result = await runner.run(assignment, {
        runId,
        // The session's host-side function-call ceiling.
        ...(sessionPolicy?.maxIterations != null
          ? { maxFunctionCalls: sessionPolicy.maxIterations }
          : {}),
        // The session's wall-clock execution timeout - the runner pauses
        // the dispatch with EXECUTION_TIMEOUT once elapsed.
        ...(sessionPolicy?.executionTimeoutSeconds != null
          ? { executionTimeoutSeconds: sessionPolicy.executionTimeoutSeconds }
          : {}),
        // The live tighten-only allowance overlay - the policy-updates
        // handler mutates this through the run handle.
        allowanceSource: allowanceSource,
        // §13: the SHARED per-attempt budget (Fix 1) - the identical
        // controller reaches the runner's TurnRun budget, the helpers
        // (setBudget above), and the snapshot publisher (Fix 5).
        budget,
        // Task 7 (14.5): the executor-composed scheduler - the runner
        // reuses THIS instance (orchestrator + helpers + interaction
        // serialize through the SAME one permit).
        ...(attemptScheduler ? { modelScheduler: attemptScheduler } : {}),
      });

      // Protocol 8.4 snapshots at the observed boundaries (Fix 5): after
      // admission + at helper-function completion, and ONE cumulative
      // usage event through the EXISTING USAGE_UPDATED type - the
      // publisher's own data block stays inside the sink's maxBytes
      // budget. Bounded: two PROGRESS-class emissions per helper call +
      // one USAGE_UPDATED at the run boundary; never per-token.
      await snapshotPublisherLocal.publish();
      this.log.info(
        `orchestration dispatch ${dispatchId} runner finished: ${result.status}`,
      );

      // Task 8 (§22.8 settle-before-terminal): when an interaction
      // runtime was composed, publish its terminal teardown FIRST - the
      // controller settles any pending interaction durably (persist
      // before publish, the §22.6 outcome) and rejects pending proposal
      // waits - and await the recorded in-flight settlements so the
      // interaction outcome frames reach the wire BEFORE the dispatch's
      // terminal frame. No runtime (composition fallback/unmapped
      // session): unchanged behavior - no settle, no teardown.
      const runtime = this.interactionRuntimes.get(dispatchId);
      if (runtime) {
        try {
          await runtime.controller.stop(cancellation.cancelled ? "CANCEL" : "TERMINAL");
        } catch (stopErr) {
          this.log.warn(
            `interaction controller stop failed for ${dispatchId}:`,
            stopErr instanceof Error ? stopErr.message : String(stopErr),
          );
        }
        // The settle-before-terminal proof point: the runtime's recorded
        // settlements include the pending interaction's handle() promise
        // whose resolution publishes the durable outcome through the
        // typed sender. A fire-and-forget cancel stop (the handleCancel
        // path) settles through the SAME promises, so awaiting here
        // flushes both teardown classes before the terminal.
        await Promise.all([...runtime.settlements.values()]);
      }

      await this.reportOutcome(
        result,
        assignment,
        executionId,
        dispatchId,
        checkout.checkoutPath,
        source.targetBranch,
      );
    } finally {
      // Task-scoped workspace release on EVERY path (protocol 9):
      // best-effort local rm of the checkout - no lease manifest, no
      // generation, no registry, no restart reconcile.
      this.dispatches.delete(dispatchId);
      this.activeRecentEvents = null;
      // Task 7 (14.2/22.8): the interaction runtime teardown. The
      // controller stop ran BEFORE the terminal (Task 8 §22.8 settle-
      // before-terminal); a dispatch that crashed BEFORE reaching it
      // (or a cancel that arrived in window) re-runs the stop here -
      // the controller's teardown is idempotent, so the settled
      // interaction is never re-published. The coordinator stops
      // admission and wakes parked gate waiters (they never re-enter
      // gated work); the scheduler rejects queued waiters.
      const runtime = this.interactionRuntimes.get(dispatchId);
      if (runtime) {
        this.interactionRuntimes.delete(dispatchId);
        // Task 10 §22.8: the session-keyed crosswalk dies with the runtime
        // (a torn-down runtime never reports interactionState evidence).
        this.options.sessions.unbindInteractionCoordinator(sessionId);
        try {
          if (!runtime.controller.stopped) {
            await runtime.controller.stop(cancellation.cancelled ? "CANCEL" : "TERMINAL");
          }
        } catch (stopErr) {
          this.log.warn(
            `interaction controller stop failed for ${dispatchId}:`,
            stopErr instanceof Error ? stopErr.message : String(stopErr),
          );
        }
        runtime.coordinator.stop(cancellation.cancelled ? "CANCEL" : "TERMINAL");
      }
      // The attempt scheduler rejects its own waiters (the runner holds
      // it through the run; the controller shares the same instance).
      try {
        if (attemptScheduler) attemptScheduler.stop();
      } catch {
        // never out of teardown
      }
      // §22.5 (Task 11 fix): clear the deadline wake on every terminal
      // path - the timer never fires after the dispatch settled.
      if (deadlineWakeTimer != null) {
        clearTimeout(deadlineWakeTimer);
        deadlineWakeTimer = null;
      }
      this.releaseCheckout(checkout.checkoutPath);
    }
  }

  /**
   * Report the runner's outcome in the protocol 9 order: push before
   * terminal, release before terminal (the release lives in the
   * dispatch's finally), terminal frames in the 8.5/8.6 shapes.
   */
  private async reportOutcome(
    result: OrchestrationRunResult,
    assignment: OrchestrationAssignment,
    executionId: string,
    dispatchId: string,
    checkoutPath: string,
    targetBranch: string,
  ): Promise<void> {
    const startedAt = Date.now();

    // COMPLETED: push the attempt's commit, then the terminal.
    if (result.status === "COMPLETED") {
      const pushFailure = await this.pushForTerminal(
        result, checkoutPath, targetBranch,
      );
      if (pushFailure) {
        await this.emitFailedTerminal(executionId, pushFailure, result, assignment);
        return;
      }
      await this.emitCompleteTerminal(executionId, result, startedAt);
      return;
    }

    // PAUSED (HITL): push the checkpoint commit, then approval.requested
    // + execution.paused (protocol 8.7 then 8.6 order).
    if (result.status === "PAUSED") {
      const pushFailure = await this.pushForTerminal(
        result, checkoutPath, targetBranch,
      );
      if (pushFailure) {
        await this.emitFailedTerminal(executionId, pushFailure, result, assignment);
        return;
      }
      await this.emitPausedTerminal(executionId, dispatchId, result, checkoutPath);
      return;
    }

    // CANCELLED: emit the terminal (no push - no committed state).
    if (result.status === "CANCELLED") {
      await this.emitCancelledTerminal(executionId, dispatchId);
      return;
    }

    // FAILED retryable: push any checkpoint commit that exists (best
    // effort), then execution.failed WITH the continuation block (the
    // engine throws on a RETRYABLE result without a continuation).
    if (result.retryDisposition === "RETRYABLE") {
      await this.pushForTerminal(result, checkoutPath, targetBranch).catch(() => undefined);
      await this.emitFailedTerminal(executionId, null, result, assignment);
      return;
    }

    // FAILED terminal: emit the terminal (no push).
    await this.emitFailedTerminal(executionId, null, result, assignment);
  }

  /**
   * Push the attempt's HEAD to the run's target branch (D10 part 1).
   * Returns a converted SOURCE_PUSH_FAILED failure when the push fails on
   * the COMPLETE/PAUSED path; null when the push succeeded (or the
   * outcome carries no committed state to push).
   *
   * The converted failure is RETRYABLE and therefore MUST carry a
   * continuation record (the engine's applyResult throws on a
   * RETRYABLE without one): the run's committed state is restorable -
   * the checkpoint commit (when one exists) is the snapshot, the
   * candidate tree at report time is the recorded state.
   */
  private async pushForTerminal(
    result: OrchestrationRunResult,
    checkoutPath: string,
    targetBranch: string,
  ): Promise<OrchestrationRunResult | null> {
    // Nothing to push when no commit exists (a failed attempt with no
    // checkpoint). The push still happens for a no-changes COMPLETE
    // (the head must exist on the target ref for the pin).
    if (result.status === "FAILED" && result.commits.length === 0) {
      return null;
    }
    try {
      const pusher = this.options.pusher ?? this.pusher;
      await pusher.push({ checkoutPath, targetBranch });
      return null;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(
        `target-branch push failed for ${result.dispatch.dispatchId}: ${message}`,
      );
      // The run's committed state is not reported as terminal success
      // when the engine cannot see it - a retryable SOURCE_PUSH_FAILED
      // with a continuation record (the engine requires one).
      const continuation = result.continuation ?? {
        continuationId: `cont-${result.dispatch.dispatchId}-push-retry`,
        continuationRef: `local:cont-${result.dispatch.dispatchId}-push-retry`,
        snapshotTreeHash:
          result.commits[0]?.treeHash ?? result.suspension?.snapshotTreeHash ?? "",
        workspaceRevision: result.suspension?.workspaceRevision ?? 0,
        stateDigest: createHash("sha256")
          .update(`${result.dispatch.dispatchId}:push:${message}`)
          .digest("hex"),
      };
      return {
        ...result,
        status: "FAILED",
        retryDisposition: "RETRYABLE",
        summary: `target-branch push failed: ${message}`.slice(0, 4000),
        errorCode: "SOURCE_PUSH_FAILED" as OrchestrationRunResult["errorCode"],
        continuation,
      };
    }
  }

  /**
   * Release the task-scoped checkout (best-effort local rm - protocol 9:
   * the checkout is released before the terminal/pause frame is sent).
   */
  private releaseCheckout(checkoutPath: string): void {
    try {
      rmSync(path.join(path.dirname(checkoutPath)), { recursive: true, force: true });
    } catch (err) {
      this.log.warn(
        `task checkout release failed for ${checkoutPath}:`,
        err instanceof Error ? err.message : String(err),
      );
    }
  }

  /**
   * Resolve the source access token: the session's credential vault when
   * the assignment's source carries a credentialRef, else the empty
   * string (as today).
   */
  private resolveSourceToken(
    sessionId: string,
    source: OrchestrationAssignment["source"],
  ): string {
    if (!source.credentialRef) {
      return "";
    }
    try {
      return this.options.sessions.resolveCredential(sessionId, source.credentialRef);
    } catch {
      // Unknown ref: no token (the empty-string behavior the workspace
      // manager already tolerates for public origins).
      return "";
    }
  }

  // -- terminal emitters (protocol 8.5/8.6/8.8 shapes) ------------

  /**
   * execution.complete: result.structured carries the FULL redacted
   * OrchestrationRunResult (dispatch identity, helper calls, verifier
   * results, command records, changed files, commits, clean worktree,
   * usage, continuation/suspension when present); result.content is a
   * short human summary; usage is the 8.5 conversation-shaped block
   * (modelId null; token totals across helpers; durationMs).
   */
  private async emitCompleteTerminal(
    executionId: string,
    result: OrchestrationRunResult,
    startedAtMs: number,
  ): Promise<void> {
    const complete: ExecutionCompletePayload = {
      executionId: executionId as ExecutionCompletePayload["executionId"],
      completedAt: new Date().toISOString(),
      result: {
        content: result.summary,
        structured: result as unknown as Record<string, unknown>,
        artifacts: null,
      },
      usage: {
        modelId: null,
        inputTokens: null,
        outputTokens: result.usage.totalTokens,
        durationMs: Math.max(0, Date.now() - startedAtMs),
      },
    };
    await this.options.sender.sendExecutionComplete(complete);
  }

  /**
   * execution.failed: error carries the orchestration errorCode with
   * retryable mirroring the result's retryDisposition, PLUS a
   * top-level continuation block when retryable (the engine requires
   * one on a RETRYABLE outcome).
   */
  private async emitFailedTerminal(
    executionId: string,
    pushFailure: OrchestrationRunResult | null,
    result: OrchestrationRunResult,
    assignment: OrchestrationAssignment,
  ): Promise<void> {
    const effective = pushFailure ?? result;
    const retryable = effective.retryDisposition === "RETRYABLE";
    const base: ExecutionFailedPayload = {
      executionId: executionId as ExecutionFailedPayload["executionId"],
      failedAt: new Date().toISOString(),
      error: {
        code: effective.errorCode ?? "ORCHESTRATOR_FAILED",
        message: effective.summary,
        category: null,
        retryable,
        retryAfterSeconds: null,
      },
      usage: {
        modelId: null,
        inputTokens: null,
        outputTokens: effective.usage.totalTokens,
        durationMs: null,
      },
    };
    // The redacted result body rides a result.structured block (the 8.5
    // success shape's evidence block; protocol 16 additive-field rule):
    // the engine's FAILED arm unwraps it the same way as COMPLETE so the
    // attempt/task rows keep the full audit evidence (helperCalls,
    // verifierResults, changedFiles, usage) for EVERY terminal, not only
    // success. The continuation block rides the payload map as an
    // error-sibling top-level field; the engine reads the raw map.
    const payload = {
      ...base,
      result: {
        content: effective.summary,
        structured: effective as unknown as Record<string, unknown>,
        artifacts: null,
      },
      ...(retryable && effective.continuation
        ? { continuation: effective.continuation }
        : {}),
    };
    await this.options.sender.sendExecutionFailed(
      payload as ExecutionFailedPayload,
    );
    void assignment;
  }

  /**
   * execution.paused: the exact protocol 8.6 orchestration shape - the
   * continuation block (recoveryProviderId "host-local", portability
   * "SAME_HOST", snapshotRef the pushed pause commit), the suspension
   * block (approval request + pending action + expiry), and the 8.6
   * usage block (orchestrationFunctionCalls, totalTokens).
   * `execution.approval.requested` is emitted FIRST (protocol 8.7
   * order) via the sink's approval path.
   */
  private async emitPausedTerminal(
    executionId: string,
    dispatchId: string,
    result: OrchestrationRunResult,
    checkoutPath: string,
  ): Promise<void> {
    const suspension = result.suspension;
    const continuation = result.suspension ?? result.continuation;
    if (!suspension || !continuation) {
      // Fail closed: a PAUSED result without a suspension record cannot
      // be reported as a resumable pause - it is a terminal failure.
      this.log.error(
        `PAUSED result for dispatch ${dispatchId} lacks a suspension record - reporting failed`,
      );
      await this.emitFailedTerminal(executionId, null, {
        ...result,
        status: "FAILED",
        retryDisposition: "TERMINAL",
        errorCode: "APPROVAL_POLICY_DENIED",
      }, result as unknown as OrchestrationAssignment);
      return;
    }
    const paused: ExecutionPausedPayload = {
      executionId: executionId as ExecutionPausedPayload["executionId"],
      pausedAt: new Date().toISOString(),
      reasonCode: suspension.reason ?? "HITL_APPROVAL",
      continuation: {
        continuationId: continuation.continuationId,
        continuationRef: continuation.continuationRef,
        recoveryProviderId: "host-local",
        portability: "SAME_HOST",
        snapshotRef: await this.headCommitOf(checkoutPath),
        ...(continuation.snapshotDigest
          ? { snapshotDigest: continuation.snapshotDigest }
          : {}),
        snapshotTreeHash: continuation.snapshotTreeHash,
        workspaceRevision: continuation.workspaceRevision,
        stateDigest: continuation.stateDigest,
      },
      suspension: suspension.approvalRequestId
        ? {
            approvalRequestId: suspension.approvalRequestId,
            pendingAction: suspension.pendingAction
              ? {
                  actionId: suspension.pendingAction.actionId,
                  type: suspension.pendingAction.type,
                  riskClass: suspension.pendingAction.riskClass,
                  summary: suspension.pendingAction.summary,
                  digest: suspension.pendingAction.digest,
                }
              : null,
            expiresAt: suspension.expiresAt ?? null,
          }
        : null,
      conversationContinuation: null,
      // Protocol 8.6: the orchestration pause usage block - the dispatch's
      // cumulative function-call + token totals from the runner result.
      usage: {
        modelId: null,
        inputTokens: null,
        outputTokens: null,
        orchestrationFunctionCalls: result.usage.helperCalls,
        totalTokens: result.usage.totalTokens,
      },
    };
    await this.options.sender.sendExecutionPaused(paused);
  }

  /** execution.cancelled: the current shape (unchanged). */
  private async emitCancelledTerminal(
    executionId: string,
    dispatchId: string,
  ): Promise<void> {
    const cancelled: ExecutionCancelledPayload = {
      executionId: executionId as ExecutionCancelledPayload["executionId"],
      dispatchId: dispatchId as ExecutionCancelledPayload["dispatchId"],
      cancelledAt: new Date().toISOString(),
      reasonCode: "USER_REQUEST",
    };
    await this.options.sender.sendExecutionCancelled(cancelled);
  }

  /**
   * execution.approval.requested (protocol 8.7): durable, acked - the
   * engine persists the request and surfaces the approval banner before
   * the terminal execution.paused arrives.
   */
  private async emitApprovalRequested(
    executionId: string,
    dispatchId: string,
    request: {
      approvalRequestId: string;
      action: {
        actionId: string;
        type: string;
        riskClass: string;
        summary: string;
        digest: string;
      };
      snapshotTreeHash: string;
      stateDigest: string;
      expiresAt: string;
    },
  ): Promise<void> {
    const payload: ExecutionApprovalRequestedPayload = {
      executionId: executionId as ExecutionApprovalRequestedPayload["executionId"],
      dispatchId: dispatchId as ExecutionApprovalRequestedPayload["dispatchId"],
      approvalRequestId: request.approvalRequestId,
      action: {
        actionId: request.action.actionId,
        type: request.action.type,
        riskClass: request.action.riskClass,
        summary: request.action.summary,
        digest: request.action.digest,
      },
      snapshotTreeHash: request.snapshotTreeHash,
      stateDigest: request.stateDigest,
      expiresAt: request.expiresAt,
    };
    await this.options.sender.sendExecutionApprovalRequested(payload);
  }

  /** The pushed pause commit sha at the checkout's HEAD. */
  private async headCommitOf(checkoutPath: string): Promise<string | null> {
    try {
      const { execFile } = await import("node:child_process");
      const { promisify } = await import("node:util");
      const exec = promisify(execFile);
      const { stdout } = await exec("git", ["rev-parse", "HEAD"], {
        cwd: checkoutPath,
        windowsHide: true,
      });
      return stdout.trim() || null;
    } catch {
      return null;
    }
  }

  // -- interaction family emitters (protocol 22.4/22.6/22.7, Task 7) --

  /**
   * Task 8 (§22.8): one settled interaction's usage rides a USAGE_UPDATED
   * orchestration event with the settlement identity block (source
   * INTERACTION, the attributed interactionId, and the deterministic
   * settlementId `interactive-settlement-<interactionId>`). The engine's
   * accounting settles by settlementId; the flat field pattern follows
   * the snapshot publisher (ExecutionSnapshot.snapshotToData) and the
   * CaptureFilter's USAGE_UPDATED allowlist admits the block at METADATA.
   *
   * Idempotent by settlementId on the executor side (a replayed
   * re-drive re-invokes onUsageSettled for the SAME interactionId - the
   * emitted set dedups so one settlement event per interaction lands).
   * Emission is best-effort: a sink failure (outbox unhealthy) is
   * logged, never thrown into the controller's settle path.
   */
  private readonly emittedInteractionSettlements = new Set<string>();

  private async emitUsageSettlement(
    settlement: {
      interactionId: string;
      usage: ExecutionInteractionCompletePayload["usage"];
      usageStatus: "KNOWN" | "UNKNOWN";
    },
    dispatch: DispatchIdentity,
    executionId: string,
    dispatchId: string,
  ): Promise<void> {
    const settlementId = `interactive-settlement-${settlement.interactionId}`;
    if (this.emittedInteractionSettlements.has(settlementId)) return;
    this.emittedInteractionSettlements.add(settlementId);
    try {
      await this.sink.emitEvent({
        dispatch,
        type: "USAGE_UPDATED",
        // The interaction's OWN tokens (the settlement's attribution
        // input); helperCalls/rejectionCount are not interaction fields
        // - they stay out of the block rather than reporting zeros.
        usage: {
          helperCalls: 0,
          rejectionCount: 0,
          totalTokens:
            (settlement.usage?.inputTokens ?? 0) +
            (settlement.usage?.outputTokens ?? 0),
        },
        source: "INTERACTION",
        interactionId: settlement.interactionId,
        settlementId,
        executionId,
        dispatchId,
        usageStatus: settlement.usageStatus,
      });
    } catch (err) {
      // The settlement id releases so a retry (a re-drive's onUsageSettled
      // after the outbox heals) can emit again - honest at-least-once.
      this.emittedInteractionSettlements.delete(settlementId);
      this.log.warn(
        "interaction usage settlement emit failed:",
        err instanceof Error ? err.message : String(err),
      );
    }
  }

  /**
   * execution.control.state (22.4): the coordinator's durable state
   * publication. The outbox record carries its OWN wire messageId
   * (persistControlState derives it); the sender bridge stamps it.
   */
  private async sendControlState(
    payload: ExecutionControlStatePayload,
  ): Promise<void> {
    await this.postOutboxFrame("execution.control.state", payload, {
      id: `ctl-${payload.executionId}-${payload.stateSequence}`,
      messageId: `ctl-${payload.executionId}-${payload.stateSequence}`,
    });
  }

  /** execution.interaction.delta (22.6): one best-effort fragment. */
  private async sendInteractionDelta(
    payload: ExecutionInteractionDeltaPayload,
  ): Promise<void> {
    await this.options.sender.sendExecutionInteractionDelta(payload);
  }

  /** execution.interaction.complete (22.6): durable outcome - the
   * controller has ALREADY persisted through the outbox seam; the
   * sender rides the record's wire identity for retransmit stability. */
  private async sendInteractionComplete(
    payload: ExecutionInteractionCompletePayload,
  ): Promise<void> {
    await this.sendInteractionOutboxFrame("interaction_complete", payload);
  }

  /** execution.interaction.failed (22.6): durable outcome. */
  private async sendInteractionFailed(
    payload: ExecutionInteractionFailedPayload,
  ): Promise<void> {
    await this.sendInteractionOutboxFrame("interaction_failed", payload);
  }

  /**
   * The no-runtime interaction settle (22.6 fail-safe): a settled FAILED
   * outcome for an interaction whose identity belongs to a known
   * session/admission but whose dispatch has no live runtime (the
   * execution never started, already finished, or the composition fell
   * back). Persist BEFORE send (22.3); interaction failure alone never
   * fails the attempt.
   */
  private async emitInteractionFailedDurable(
    source: ExecutionInteractionPayload,
    error: { errorCode: ExecutionInteractionFailedPayload["error"]["errorCode"]; message: string },
  ): Promise<void> {
    const payload: ExecutionInteractionFailedPayload = {
      executionId: source.executionId,
      dispatchId: source.dispatchId,
      interactionId: source.interactionId,
      ordinal: source.ordinal,
      error: {
        errorCode: error.errorCode,
        message: error.message.slice(0, 2000),
        retryable: false,
      },
      usage: null,
      usageStatus: "UNKNOWN",
      controlRequestIds: [],
      completedAt: new Date().toISOString(),
    };
    await this.outbox.persistInteractionFailed(payload);
    await this.sendInteractionOutboxFrame("interaction_failed", payload);
  }

  /** execution.control.request (22.7): the durable proposal - same
   * persist-before-send rule (the controller persisted already). */
  private async sendControlRequest(
    payload: ExecutionControlRequestPayload,
  ): Promise<void> {
    await this.sendInteractionOutboxFrame("control_request", payload);
  }

  /**
   * The interaction-family outbox bridge: the record was enqueued by the
   * persist seam (its messageId IS the record id); re-send through the
   * typed sender with the messageIdOverride stamp (12.1 stability).
   */
  private async sendInteractionOutboxFrame(
    kind: "interaction_complete" | "interaction_failed" | "control_request",
    payload: Record<string, unknown>,
  ): Promise<void> {
    const frameType = kind === "interaction_complete"
      ? "execution.interaction.complete"
      : kind === "interaction_failed"
        ? "execution.interaction.failed"
        : "execution.control.request";
    const recordId = kind === "control_request"
      ? `cr-${String(payload.controlRequestId)}`
      : `interaction-${String(payload.interactionId)}-${kind === "interaction_complete" ? "complete" : "failed"}`;
    await this.postOutboxFrame(frameType, payload, { id: recordId, messageId: recordId });
  }

  /**
   * Record ONE published event's sanitized metadata into the safe-events
   * ring (14.6: the interaction surface gets the SAME metadata columns
   * the engine's execution.event saw - no new content, no secrets).
   */
  private async recordSafeEvent(event: Record<string, unknown>): Promise<void> {
    const recentEvents = this.activeRecentEvents;
    if (!recentEvents) return;
    const { dispatch, ...rest } = event;
    void dispatch;
    const { schemaVersion: _s, eventId: _e, sequence: _q,
            occurredAt: _o, type: _t, ...data } = rest;
    void _s; void _e; void _q; void _o;
    recentEvents.record({
      type: (event.type as string) ?? "",
      at: (event.occurredAt as string) ?? new Date().toISOString(),
      data: data as Record<string, unknown>,
    });
  }

  /** The ring of the EXECUTING dispatch (the serialized one-at-a-time
   * chain makes the active ring unambiguous). */
  private activeRecentEvents: SafeExecutionEventRing | null = null;

  /**
   * The sink-outbox bridge: post one outbox frame through the typed
   * sender. The frame's messageId is stamped from the record (derived
   * deterministically BEFORE the first send so retransmissions reuse it)
   * and the envelope sequence rides the record's dispatch-local sequence;
   * the client consumes both override hints and never mints fresh values
   * for a retransmitted record (protocol 12.1: a fresh messageId or
   * sequence would collide with the engine's dedup/slot-uniqueness as a
   * conflicting duplicate). A control_state record's `commandMessageId`
   * rides as `correlationIdOverride` (protocol 22.4: command-driven
   * states correlate to the originating command's messageId on the
   * envelope correlation; timer-driven states carry none).
   */
  private async postOutboxFrame(
    type: string,
    payload: unknown,
    record: {
      id: string;
      messageId?: string;
      sequence?: number;
      commandMessageId?: string;
    },
  ): Promise<void> {
    // The record's messageId, derived deterministically from the record
    // id so every send of this record reuses the SAME wire identity.
    const messageId = record.messageId ?? `obx-${record.id}`;
    const body = payload as Record<string, unknown>;
    const stamped = {
      ...body,
      messageIdOverride: messageId,
      // 22.4 command correlation: only control_state records carry this.
      ...(record.commandMessageId !== undefined
        ? { correlationIdOverride: record.commandMessageId }
        : {}),
    };
    switch (type) {
      case "execution.event":
        await this.options.sender.sendExecutionEvent({
          executionId: this.executionIdOfRecord(body),
          eventId: (body.eventId as string) ?? record.id,
          eventType: (body.type as string) ?? "",
          occurredAt: (body.occurredAt as string) ?? new Date().toISOString(),
          // The sink's filtered+truncated payload rides as the event
          // data block (status/usage/snapshot/progress metadata) - the
          // engine stores it verbatim as the event's data.
          data: (() => {
            const { schemaVersion: _s, eventId: _e, dispatch: _d, type: _t,
                    sequence: _q, occurredAt: _o, ...rest } = body;
            void _s; void _e; void _d; void _t; void _q; void _o;
            return rest as Record<string, unknown>;
          })(),
          // The envelope sequence: the record's dispatch-local monotonic
          // sequence, stable across retransmits.
          sequenceOverride: record.sequence ?? undefined,
        } as Parameters<ExecutionFrameSender["sendExecutionEvent"]>[0] & {
          sequenceOverride?: number;
        });
        return;
      case "execution.complete":
        await this.options.sender.sendExecutionComplete(
          stamped as unknown as ExecutionCompletePayload,
        );
        return;
      case "execution.failed":
        await this.options.sender.sendExecutionFailed(
          stamped as unknown as ExecutionFailedPayload,
        );
        return;
      case "execution.paused":
        await this.options.sender.sendExecutionPaused(
          stamped as unknown as ExecutionPausedPayload,
        );
        return;
      case "execution.approval.requested":
        await this.options.sender.sendExecutionApprovalRequested(
          stamped as unknown as ExecutionApprovalRequestedPayload,
        );
        return;
      // -- Task 7 protocol 22.4/22.6/22.7 arms --
      case "execution.control.state":
        await this.options.sender.sendExecutionControlState(
          stamped as unknown as ExecutionControlStatePayload,
        );
        return;
      case "execution.interaction.complete":
        await this.options.sender.sendExecutionInteractionComplete(
          stamped as unknown as ExecutionInteractionCompletePayload,
        );
        return;
      case "execution.interaction.failed":
        await this.options.sender.sendExecutionInteractionFailed(
          stamped as unknown as ExecutionInteractionFailedPayload,
        );
        return;
      case "execution.control.request":
        await this.options.sender.sendExecutionControlRequest(
          stamped as unknown as ExecutionControlRequestPayload,
        );
        return;
      default:
        this.log.warn(`outbox bridge: unknown frame type ${type} - dropped`);
    }
  }

  /**
   * The executionId an outbox event payload is bound to. Event payloads
   * carry the dispatch identity; the executor maps the live dispatch's
   * executionId through the dispatches registry.
   */
  private executionIdOfRecord(body: Record<string, unknown>): string {
    const dispatch = body.dispatch as
      | { dispatchId?: string }
      | undefined;
    const dispatchId = dispatch?.dispatchId;
    if (dispatchId) {
      const live = this.dispatches.get(dispatchId);
      if (live) return live.executionId;
      for (const [, s] of this.dispatches) {
        if (s.executionId === dispatchId) return dispatchId;
      }
      // The dispatch may have already terminated - fall back to the
      // dispatch id itself so the frame still carries a syntactically
      // valid execution identity.
      return dispatchId;
    }
    return randomUUID();
  }
}