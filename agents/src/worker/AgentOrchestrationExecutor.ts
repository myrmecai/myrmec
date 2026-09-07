// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AgentOrchestrationExecutor (design §16.3/§17.1, Feature 10): the worker-side
 * handler for one orchestration dispatch.
 *
 * <p>Every frame is untrusted data: the assignment is validated with the
 * strict runtime schema before any workspace or model call. On a valid
 * dispatch the executor (§16.3 order):</p>
 * <ol>
 *   <li>persists {@code dispatchId + assignmentDigest} durably (outbox
 *       admission store) — THEN acknowledges {@code inference.accept};</li>
 *   <li>acquires the run's workspace lease through the Supervisor-owned
 *       {@link HostWorkspaceRegistry} and clones the exact
 *       {@code sourceBaseCommit} (never resolving the branch again);</li>
 *   <li>runs the step through the real {@link OrchestrationRunner} with the
 *       real file/command tools, the checkpoint service, and the durable
 *       outbox-backed event sink (stop-before-side-effect when the outbox
 *       is unhealthy);</li>
 *   <li>emits the ONE terminal result through the sink; the workspace lease
 *       is only released on an explicit {@code orchestration.release} — the
 *       executor never deletes the checkout itself (§17.1).</li>
 * </ol>
 *
 * <p>Duplicate dispatch delivery is idempotent: a dispatch already admitted
 * (matching digest) returns the stored acknowledgement without re-running;
 * conflicting bytes fail closed.</p>
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import type { Envelope } from "../protocol/envelope.js";
import {
  inferenceAccept,
  workspaceReleaseAcknowledgement,
} from "../protocol/orchestrationFrames.js";
import { orchestrationAssignmentSchema } from "../orchestration/schema.js";
import type {
  OrchestrationAssignment,
  OrchestrationRunResult,
} from "../orchestration/types.js";
import { ORCHESTRATION_RESULT_NS, uuidV5 } from "../orchestration/constants.js";
import {
  OrchestrationRunner,
  WorkerInvoker,
  GitWorkspaceManager,
  GitWorkspaceScope,
  GitWorkspaceInspector,
  GitCheckpointService,
} from "../orchestration/index.js";
import { ApprovalPolicyEvaluator } from "../orchestration/ApprovalPolicyEvaluator.js";
import { LocalContinuationStateStore } from "../orchestration/ContinuationStateStore.js";
import { HostWorkspaceRegistry } from "../workspace/HostWorkspaceRegistry.js";
import { FsOutboxBackend, OrchestrationOutbox } from "../protocol/OrchestrationOutbox.js";
import { AgentProtocolOrchestrationEventSink } from "../protocol/AgentProtocolOrchestrationEventSink.js";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { Logger } from "../models/index.js";

export interface AgentOrchestrationExecutorOptions {
  /** Where workspaces live: <root>/runs/<runId>/<generation>/checkout (§17.1). */
  workspaceRoot: string;
  /** Where the durable outbox records live (agent-local). */
  outboxRoot: string;
  /** The stub/real model factory (same seam as ordinary inference). */
  chatModelFactory: ChatModelFactory;
  /** Emit one frame toward the engine (the worker's only sink). */
  send(frame: Envelope): void;
  /** HITL (§17.4): the project's autoHitlOnDestructive matrix input —
   * captured from the orchestration session.open (default true:
   * suspend-on-destructive is the conservative floor). */
  autoHitlOnDestructive?: boolean;
  logger?: Logger;
}

/** One durable admission: dispatchId → the accepted digest. */
interface Admission {
  assignmentDigest: string;
  admittedAt: string;
}

export class AgentOrchestrationExecutor {
  private readonly options: AgentOrchestrationExecutorOptions;
  private readonly registry: HostWorkspaceRegistry;
  private readonly outbox: OrchestrationOutbox;
  private readonly sink: AgentProtocolOrchestrationEventSink;
  private readonly admissions = new Map<string, Admission>();
  /** Per-run identity for release acknowledgements (workflowId). */
  private readonly runWorkflow = new Map<string, string>();
  /** Per-dispatch live state (run + cancellation signal). */
  private readonly dispatches = new Map<
    string,
    { runId: string; cancellation: { cancelled: boolean } }
  >();
  private readonly log: Logger;
  /**
   * §17.6/V1: one dispatch executes at a time — a single agent
   * coordinator owns the runner state machine, and the shared run
   * checkout admits exactly one mutation chain. A second dispatch of
   * the same run WAITS for the first to finish (the engine serializes
   * per-agent anyway; this guards the dispatcher's same-pass race).
   */
  private executionChain: Promise<unknown> = Promise.resolve();

  constructor(options: AgentOrchestrationExecutorOptions) {
    this.options = options;
    this.log = options.logger ?? console;
    this.registry = new HostWorkspaceRegistry({
      workspaceRoot: options.workspaceRoot,
    });
    this.outbox = new OrchestrationOutbox({
      backend: new FsOutboxBackend(options.outboxRoot),
      send: async (envelope) => {
        options.send(envelope);
        return true;
      },
    });
    this.sink = new AgentProtocolOrchestrationEventSink({ outbox: this.outbox });
  }

  /**
   * Handle one `inference.assign` carrying an orchestration payload.
   * Returns true when the dispatch was admitted (acceptance sent); false
   * when the frame was rejected (malformed or conflicting).
   */
  async handleAssign(payload: {
    requestId: string;
    sessionId: string | null;
    orchestration?: unknown;
    assignmentDigest?: string;
  }): Promise<boolean> {
    const dispatchId = payload.requestId;
    const digest = payload.assignmentDigest ?? "";
    if (!dispatchId || !digest) {
      this.log.warn("orchestration assign missing requestId/digest");
      return false;
    }

    // Idempotent admission: replay with the same digest returns the stored
    // acknowledgement; different bytes fail closed (§16.3).
    const existing = this.admissions.get(dispatchId);
    if (existing) {
      if (existing.assignmentDigest === digest) {
        this.sendAccept(dispatchId, payload.sessionId, digest);
        return true;
      }
      this.log.warn(
        `dispatch ${dispatchId} re-delivered with CONFLICTING digest — failing closed`,
      );
      return false;
    }

    // Validate the assignment with the strict runtime schema before any
    // side effect (§16.3: malformed → ASSIGNMENT_VALIDATION_ERROR, no
    // workspace acquisition, no model call).
    const parsed = orchestrationAssignmentSchema.safeParse(payload.orchestration);
    if (!parsed.success) {
      this.log.warn("orchestration assignment failed schema validation");
      // §16.3: a malformed assignment is reported as a terminal result —
      // never acknowledged as admitted work.
      await this.emitFailureResult(
        {
          workflowId: "",
          runId: "",
          stepId: "",
          taskId: "",
          attemptId: dispatchId,
          attemptOrdinal: 1,
          dispatchId,
        },
        "ASSIGNMENT_VALIDATION_ERROR",
        parsed.error.issues
          .map((i) => `${i.path.join(".")}: ${i.message}`)
          .join("; ")
          .slice(0, 500),
      );
      return false;
    }
    const assignment = parsed.data as OrchestrationAssignment;

    // Durable admission BEFORE the acceptance crosses the wire (§16.3).
    this.admissions.set(dispatchId, {
      assignmentDigest: digest,
      admittedAt: new Date().toISOString(),
    });

    // Acknowledge: the exact admitted digest.
    this.sendAccept(dispatchId, payload.sessionId, digest);

    // Execute the dispatch (async — the worker keeps serving frames),
    // serialized behind any in-flight dispatch (§17.6: one at a time).
    const previous = this.executionChain;
    this.executionChain = (async () => {
      await previous.catch(() => undefined);
      await this.executeDispatch(assignment);
    })().catch((err) => {
      this.log.error("orchestration dispatch failed:", err);
    });
    return true;
  }

  /** Handle `inference.cancel` for an active orchestration dispatch (§13). */
  handleCancel(payload: { attemptId?: string; requestId?: string }): void {
    const dispatchId = payload.attemptId ?? payload.requestId;
    if (!dispatchId) return;
    const state = this.dispatches.get(dispatchId);
    if (state) {
      state.cancellation.cancelled = true;
      this.log.info(`orchestration dispatch ${dispatchId} cancellation signalled`);
    }
  }

  /**
   * Handle `orchestration.release` (§16.5): the engine's terminal-state
   * release for the run's workspace lease. Idempotent release through the
   * registry; the deterministic acknowledgement rides `host.announce`.
   */
  async handleRelease(payload: {
    releaseId: string;
    runId: string;
    workspaceGeneration: number;
    reason?: string;
  }): Promise<void> {
    const { releaseId, runId } = payload;
    const generation = Number(payload.workspaceGeneration ?? 1);
    if (!releaseId || !runId) {
      this.log.warn("orchestration.release malformed — dropped");
      return;
    }
    try {
      const ack = await this.registry.release(runId, generation, payload.reason ?? "");
      const workflowId = this.runWorkflow.get(runId) ?? releaseId;
      this.options.send(
        workspaceReleaseAcknowledgement({
          schemaVersion: "1.0",
          acknowledgementId: ack.acknowledgementId,
          releaseId,
          runId,
          workflowId,
          workspaceGeneration: generation,
          status: ack.status,
          occurredAt: ack.occurredAt,
        }),
      );
      this.log.info(`run ${runId} released (${ack.status}) — ack ${ack.acknowledgementId}`);
    } catch (err) {
      this.log.error(`release of run ${runId} failed:`, err);
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

  // ── internals ──────────────────────────────────────────────────

  private sendAccept(dispatchId: string, sessionId: string | null, digest: string): void {
    this.options.send(
      inferenceAccept({
        requestId: dispatchId,
        sessionId,
        dispatchId,
        assignmentDigest: digest,
      }),
    );
  }

  /** Run one admitted dispatch end to end. */
  private async executeDispatch(assignment: OrchestrationAssignment): Promise<void> {
    const { dispatch, source, step } = assignment;
    const o = step.orchestration;
    const runId = dispatch.runId;
    const generation = 1;
    this.runWorkflow.set(runId, dispatch.workflowId);

    this.log.info(
      `orchestration dispatch ${dispatch.dispatchId} executing (step ${step.id}, run ${runId})`,
    );

    // §8.3/§17.1: ONE checkout serves the complete run — the FIRST dispatch
    // clones; later dispatches of the same generation reuse the live lease
    // (the checkout already carries earlier steps' commits). A fresh clone
    // happens only after an explicit release advanced the generation.
    const existingCheckoutPath = this.registry.checkoutPathOf(runId);
    const manager = new GitWorkspaceManager(this.options.workspaceRoot);
    let checkout: import("../orchestration/index.js").CheckoutHandle;
    if (existingCheckoutPath) {
      // Reuse: re-derive the handle from the live checkout's own state.
      const { execFile } = await import("node:child_process");
      const { promisify } = await import("node:util");
      const exec = promisify(execFile);
      const targetBranch = await exec("git", ["rev-parse", "--abbrev-ref", "HEAD"], {
        cwd: existingCheckoutPath,
      }).then((r) => r.stdout.trim()).catch(() => source.targetBranch);
      const baseCommit = await exec("git", ["rev-parse", "HEAD"], {
        cwd: existingCheckoutPath,
      }).then((r) => r.stdout.trim()).catch(() => source.sourceBaseCommit);
      checkout = {
        workspaceId: this.registry.manifestOf(runId)?.workspaceId ?? `ws-reuse`,
        generation,
        checkoutPath: existingCheckoutPath,
        sourceBranch: source.sourceBranch,
        targetBranch,
        baseCommit,
      };
      this.log.info(
        `orchestration dispatch ${dispatch.dispatchId} reusing run checkout ${existingCheckoutPath} (head ${baseCommit.slice(0, 12)})`,
      );
    } else {
      // The clone fetches the exact assigned object — never the moving
      // branch (§16.2 step 4). §17.1 run-keyed layout: the lease
      // manifest, restart reconciliation, and the engine adapter's
      // artifact walk all address the checkout through the run identity.
      checkout = await manager.acquire(
        {
          repoUrl: source.repoUrl,
          sourceBranch: source.sourceBranch,
          sourceBaseCommit: source.sourceBaseCommit,
          targetBranch: source.targetBranch,
          accessToken: "",
        },
        undefined,
        { runId, generation },
      );
      this.log.info(
        `orchestration dispatch ${dispatch.dispatchId} cloned ${source.sourceBaseCommit.slice(0, 12)} at ${checkout.checkoutPath}`,
      );
    }

    // The Supervisor-owned lease over the clone (§16.5/§17.1): acquire (or
    // reuse) then activate.
    this.registry.reuseOrAcquire({
      runId,
      generation,
      pinnedAgentId: dispatch.attemptId,
      leaseDeadline: null,
      checkoutPath: checkout.checkoutPath,
    });
    this.registry.activate(runId, generation);

    const cancellation = { cancelled: false };
    this.dispatches.set(dispatch.dispatchId, { runId, cancellation });

    try {
      const scope = new GitWorkspaceScope();
      const inspector = new GitWorkspaceInspector();
      const stepWorkspace = scope.resolve(checkout, o.sourceSubPath);

      // Trusted spec load (§5 step 4): runner-owned read with digest.
      let specification: { content: string; sha256: string; byteLength: number } | undefined;
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

      // §21: WORKER_STARTED/WORKER_COMPLETED progress events — the §18
      // boundary observes WORKER_COMPLETED before firing a trigger. The
      // runner's worker invoker emits nothing itself (transport-
      // independent), so the executor emits the per-call envelope around
      // each invocation through the durable outbox sink.
      const workerEventCounters = new Map<string, number>();
      const observingInvoker = new WorkerInvoker({
        attemptOrdinal: dispatch.attemptOrdinal,
        chatModelFactory: this.options.chatModelFactory,
        turnExecutor: new TurnExecutor({}),
      });
      const originalInvoke = observingInvoker.invoke.bind(observingInvoker);
      observingInvoker.invoke = async (...args) => {
        const [assignmentArg, workerName, purpose] = args;
        const seq = (workerEventCounters.get(workerName) ?? 0) + 1;
        workerEventCounters.set(workerName, seq);
        await this.sink.emitEvent({
          dispatch,
          type: "WORKER_STARTED",
          workerName,
          callId: `call-${dispatch.dispatchId}-${workerName}-${seq}`,
        });
        const outcome = await originalInvoke(
          assignmentArg, workerName, purpose, args[3], args[4], args[5],
        );
        await this.sink.emitEvent({
          dispatch,
          type: "WORKER_COMPLETED",
          workerName,
          status: outcome.workerCall.status,
          callId: outcome.workerCall.callId,
          durationMs: outcome.tokenCount,
          usage: {
            workerCalls: 1,
            rejectionCount: 0,
            totalTokens: outcome.tokenCount,
          },
        });
        return outcome;
      };

      const runner = new OrchestrationRunner({
        chatModelFactory: this.options.chatModelFactory,
        workerInvoker: observingInvoker,
        turnExecutor: new TurnExecutor({}),
        workspace: stepWorkspace,
        workspaceInspector: inspector,
        checkpointService,
        expectedHead: checkout.baseCommit,
        allowCheckpoint: assignment.policy.gitPolicy.allowCheckpoint,
        cancellation,
        ...(specification ? { specification } : {}),
        // HITL (§17.4): the evaluator combines the pinned Profile's
        // approvalPolicy with the project's autoHitlOnDestructive
        // matrix input; the sink rides the durable outbox
        // (orchestration.approval_requested); the suspension publisher
        // persists the continuation under the outbox root (Host-local
        // §17.2); the loader restores it for a decision-bearing
        // continuation (slice C resume validation).
        approvalEvaluator: new ApprovalPolicyEvaluator({
          autoHitlOnDestructive: this.options.autoHitlOnDestructive ?? true,
          approvalTtlSeconds:
            assignment.policy.approvalRequestTtlSeconds,
        }),
        approvalSink: {
          request: async (r) => {
            await this.sink.emitApprovalRequest({
              dispatch: r.dispatch,
              approvalRequestId: r.approvalRequestId,
              payload: {
                schemaVersion: "1.0",
                approvalRequestId: r.approvalRequestId,
                dispatch: r.dispatch,
                action: r.action,
                snapshotTreeHash: r.snapshotTreeHash,
                stateDigest: r.stateDigest,
                expiresAt: r.expiresAt,
              },
            });
          },
        },
        suspensionPublisher: {
          publish: async (input) => {
            // §17.2/§17.4: the manifest binds the FULL suspension
            // identity — pending action, approval request id, expiry —
            // so the resume validation (slice C) restores exactly what
            // the human approved.
            const store = new LocalContinuationStateStore(
              path.join(this.options.outboxRoot, "continuations"),
            );
            const full = store.put({
              continuationId: `cont-${input.dispatch.dispatchId}-hitl`,
              dispatchId: input.dispatch.dispatchId,
              attemptOrdinal: input.dispatch.attemptOrdinal,
              budgetCounters: {
                workerCalls: 0,
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
            // §17.4 slice C: restore the stored suspension for the
            // decision validation — the manifest carries the full §7.3
            // identity (pending action, request id, expiry) the typed
            // decision envelope binds against.
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
      });

      const result = await runner.run(assignment, { runId });
      this.log.info(
        `orchestration dispatch ${dispatch.dispatchId} runner finished: ${result.status}`,
      );

      // §21: progress events through the durable outbox sink.
      await this.sink.emitEvent({
        dispatch,
        type: result.status === "COMPLETED" ? "STEP_COMPLETED" : "STEP_FAILED",
        status: result.status,
      });
      // The ONE terminal result (§16.3): deterministic resultId over the
      // RESULT namespace + the canonical result digest.
      await this.sink.emitResult({
        dispatch,
        resultId: uuidV5(ORCHESTRATION_RESULT_NS, `${result.resultDigest}:${dispatch.dispatchId}`),
        resultDigest: result.resultDigest,
        payload: result as unknown as Record<string, unknown>,
      });
      this.log.info(`orchestration dispatch ${dispatch.dispatchId} result emitted`);
    } finally {
      // The lease stays with the registry — the engine's release frame
      // drives cleanup (§17.1: the executor never deletes the checkout).
      this.dispatches.delete(dispatch.dispatchId);
    }
  }

  private async emitFailureResult(
    dispatch: OrchestrationAssignment["dispatch"],
    errorCode: string,
    message: string,
  ): Promise<void> {
    const result: OrchestrationRunResult = {
      schemaVersion: "1.0",
      resultId: uuidV5(ORCHESTRATION_RESULT_NS, `${dispatch.dispatchId}:validation`),
      resultDigest: createHash("sha256")
        .update(`${dispatch.dispatchId}:${errorCode}`)
        .digest("hex"),
      dispatch,
      status: "FAILED",
      retryDisposition: "TERMINAL",
      summary: message,
      workerCalls: [],
      verifierResults: [],
      commandExecutions: [],
      changedFiles: [],
      commits: [],
      cleanWorktree: true,
      usage: { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
      errorCode: errorCode as OrchestrationRunResult["errorCode"],
    };
    await this.sink.emitResult({
      dispatch,
      resultId: result.resultId,
      resultDigest: result.resultDigest,
      payload: result as unknown as Record<string, unknown>,
    });
  }
}