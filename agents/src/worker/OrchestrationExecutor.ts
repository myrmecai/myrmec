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
} from "../orchestration/types.js";
import { TargetBranchPusher } from "../orchestration/TargetBranchPusher.js";
import { ApprovalPolicyEvaluator } from "../orchestration/ApprovalPolicyEvaluator.js";
import { LocalContinuationStateStore } from "../orchestration/ContinuationStateStore.js";
import { FsOutboxBackend, OrchestrationOutbox } from "../protocol/OrchestrationOutbox.js";
import { AgentProtocolOrchestrationEventSink } from "../protocol/AgentProtocolOrchestrationEventSink.js";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { ExecutionFrameSender } from "../executor/ExecutionFrameSender.js";
import type { SessionRegistry } from "../session/SessionRegistry.js";
import type {
  ExecutionAcceptPayload,
  ExecutionCompletePayload,
  ExecutionFailedPayload,
  ExecutionPausedPayload,
  ExecutionCancelledPayload,
  ExecutionApprovalRequestedPayload,
  SessionPolicy,
  CapturePolicy,
} from "../protocol/unifiedFrames.js";
import type { Logger } from "../models/index.js";

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
  /** Per-dispatch live state (run + cancellation signal + executionId). */
  private readonly dispatches = new Map<
    string,
    { executionId: string; cancellation: { cancelled: boolean } }
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

    // Durable admission BEFORE the acceptance crosses the wire.
    this.admissions.set(dispatchId, {
      assignmentDigest: digest,
      admittedAt: new Date().toISOString(),
    });

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
   * executionId.
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
    this.dispatches.set(dispatchId, { executionId, cancellation });

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
        const outcome = await originalInvoke(
          assignmentArg, helperName, purpose, args[3], args[4], args[5],
        );
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
        return outcome;
      };

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
      });

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
      });
      this.log.info(
        `orchestration dispatch ${dispatchId} runner finished: ${result.status}`,
      );

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

  /**
   * The sink-outbox bridge: post one outbox frame through the typed
   * sender. The frame's messageId is stamped from the record (derived
   * deterministically BEFORE the first send so retransmissions reuse it)
   * and the envelope sequence rides the record's dispatch-local sequence;
   * the client consumes both override hints and never mints fresh values
   * for a retransmitted record (protocol 12.1: a fresh messageId or
   * sequence would collide with the engine's dedup/slot-uniqueness as a
   * conflicting duplicate).
   */
  private async postOutboxFrame(
    type: string,
    payload: unknown,
    record: { id: string; messageId?: string; sequence?: number },
  ): Promise<void> {
    // The record's messageId, derived deterministically from the record
    // id so every send of this record reuses the SAME wire identity.
    const messageId = record.messageId ?? `obx-${record.id}`;
    const body = payload as Record<string, unknown>;
    const stamped = { ...body, messageIdOverride: messageId };
    switch (type) {
      case "execution.event":
        await this.options.sender.sendExecutionEvent({
          executionId: this.executionIdOfRecord(body),
          eventId: (body.eventId as string) ?? record.id,
          eventType: (body.type as string) ?? "",
          occurredAt: (body.occurredAt as string) ?? new Date().toISOString(),
          data: (body.data as Record<string, unknown>) ?? {},
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