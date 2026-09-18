// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * AgentWorker: the in-isolate Agent body.
 *
 * This is the byte-identical worker the locked SDK design (§9.3) places below
 * both Supervisors — it runs the task and conversation dispatchers and knows
 * nothing about where its output goes. Frames it would put on the wire are
 * handed to {@link AgentWorkerOptions.post}; the Supervisor decides whether
 * those reach the engine only (headless) or the editor too (interactive). It
 * resolves its own model per task/turn from the engine descriptor — live model
 * adapters cannot cross the thread boundary, so the worker constructs them.
 */
import { MessageType as UnifiedMessageType } from "../protocol/unifiedFrames.js";
import { makeEnvelope } from "../protocol/envelope.js";
import type { Logger } from "../models/index.js";
import { InferenceExecutor } from "../executor/InferenceExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import { ApprovalCoordinator } from "../executor/ApprovalCoordinator.js";
import type {
  ExecutionCancelPayload,
  ExecutionStartPayload,
} from "../protocol/unifiedFrames.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";
import { AgentOrchestrationExecutor } from "./AgentOrchestrationExecutor.js";
import type { ExecutionFrameSender } from "../executor/ExecutionFrameSender.js";

export interface AgentWorkerOptions {
  /** Emit a frame back to the Supervisor for routing (the worker's only sink). */
  post: (message: WorkerOutbound) => void;
  /** Factory that resolves a ChatModel for each session. */
  chatModelFactory: ChatModelFactory;
  /** Factory that resolves tool implementations for each session. */
  sessionToolFactory: SessionToolFactory;
  /** Iteration cap forwarded to the executor. */
  maxIterations?: number;
  /** HTTP client for engine RPC (retrieval, etc.). Optional. */
  httpClient?: EngineHttpClient;
  /** Current agent access token for auth on RPC calls. Optional. */
  agentAccessToken?: string;
  logger?: Logger;
  /** Feature 10 (§17.1): workspace root for orchestration runs. Required
   * for orchestration dispatches; absent → orchestration assigns fail
   * closed with ASSIGNMENT_VALIDATION_ERROR. */
  workspaceRoot?: string;
  /** Feature 10 (§16.3): durable outbox root. */
  outboxRoot?: string;
  /** HITL (§17.4): the orchestration project's autoHitlOnDestructive
   * matrix input (from session.open; conservative default true). */
  autoHitlOnDestructive?: boolean;
}

export class AgentWorker {
  private readonly sessions: SessionRegistry;
  private readonly inference: InferenceExecutor;
  private readonly orchestration: AgentOrchestrationExecutor | null;
  private readonly log: Logger;
  private readonly executionSender: ExecutionFrameSender;

  constructor(options: AgentWorkerOptions) {
    this.log = options.logger ?? console;

    // Unified outbound sender: every execution.* frame the worker produces
    // is wrapped in the legacy Envelope shape (type is the unified frame
    // family) and posted back to the Supervisor for routing.
    this.executionSender = this.buildExecutionSender(options.post);

    // Unified Inference Dispatch (§6–§7)
    this.sessions = new SessionRegistry({
      chatModelFactory: options.chatModelFactory,
      sessionToolFactory: options.sessionToolFactory,
      logger: this.log,
    });

    // Feature 10 (§16.3/§17.1): the orchestration dispatch handler — only
    // constructed when the Host configured a workspace root. Without one,
    // an orchestration assign fails closed (the runner never executes).
    // HITL (§17.4): the orchestration session's autoHitlOnDestructive
    // flows from session.open — captured here so the evaluator's matrix
    // input matches the project (conservative default: suspend).
    this.orchestration = options.workspaceRoot
      ? new AgentOrchestrationExecutor({
          workspaceRoot: options.workspaceRoot,
          outboxRoot: options.outboxRoot ?? `${options.workspaceRoot}/outbox`,
          chatModelFactory: options.chatModelFactory,
          send: (frame) => void options.post({ kind: "frame", frame }),
          autoHitlOnDestructive: options.autoHitlOnDestructive ?? true,
          logger: this.log,
        })
      : null;

    // HITL approval coordinator — emits execution.approval.requested
    // (unified) when an executionId is present, falling back to legacy
    // approval.request for the conversation path until Task 6 deletes it.
    const approvals = new ApprovalCoordinator({
      sender: this.executionSender,
      logger: this.log,
    });

    this.inference = new InferenceExecutor({
      registry: this.sessions,
      sender: this.executionSender,
      httpClient: options.httpClient,
      agentAccessToken: options.agentAccessToken,
      maxIterations: options.maxIterations,
      approvals,
      logger: this.log,
    });
  }

  /** Build an {@link ExecutionFrameSender} that posts unified frames back
   * through the worker's outbound sink. */
  private buildExecutionSender(
    post: (message: WorkerOutbound) => void,
  ): ExecutionFrameSender {
    const send =
      <T extends Record<string, unknown>>(
        type: string,
        payload: T,
      ): Promise<void> => {
        post({ kind: "frame", frame: makeEnvelope(type, payload) });
        return Promise.resolve();
      };
    return {
      sendExecutionAccept: (p) => send("execution.accept", p as Record<string, unknown>),
      sendExecutionReject: (p) => send("execution.reject", p as Record<string, unknown>),
      sendExecutionDelta: (p) => send("execution.delta", p as Record<string, unknown>),
      sendExecutionEvent: (p) => send("execution.event", p as Record<string, unknown>),
      sendExecutionComplete: (p) => send("execution.complete", p as Record<string, unknown>),
      sendExecutionFailed: (p) => send("execution.failed", p as Record<string, unknown>),
      sendExecutionPaused: (p) => send("execution.paused", p as Record<string, unknown>),
      sendExecutionCancelled: (p) =>
        send("execution.cancelled", p as Record<string, unknown>),
      sendExecutionCancel: (p) => send("execution.cancel", p as Record<string, unknown>),
      sendExecutionApprovalRequested: (p) =>
        send("execution.approval.requested", p as Record<string, unknown>),
      sendProtocolError: (p) => send("protocol.error", p as Record<string, unknown>),
    };
  }

  /** True while an inference turn is executing. */
  get isBusy(): boolean {
    return this.inference.isBusy;
  }

  /**
   * PSK receive path (design 2026-09-16-credential-envelope-delivery.md
   * §6/§10): the transport invokes this with the run PSK from
   * `host.opened`; the registry keeps it in process memory only and uses it
   * to derive per-session keys for envelope unwrapping.
   */
  setPsk(psk: Uint8Array): void {
    this.sessions.setPsk(psk);
  }

  /** Dispatch an inbound message from the Supervisor. */
  async handle(message: WorkerInbound): Promise<void> {
    if (message.kind !== "envelope") {
      this.log.warn("AgentWorker: unknown inbound message", message);
      return;
    }
    const { frame } = message;
    switch (frame.type) {
      // ── Unified Inference Dispatch (§5) ──
      case "session.open":
        await this.handleSessionOpen(frame.payload);
        return;
      case "session.close":
        this.handleSessionClose(frame.payload);
        return;
      case "execution.start":
        await this.handleExecutionStart(frame.payload as ExecutionStartPayload);
        return;
      case "execution.cancel":
        this.handleExecutionCancel(frame.payload as ExecutionCancelPayload);
        return;
      case "approval.decision":
        // §8.6 live-execution delivery: the verdict unblocks a handler
        // awaiting ctx.requestApproval. The §8.6 durable path is the engine
        // row → next turn's conversationContinuation; this branch covers a
        // host-authored decision frame when the engine pushes one.
        this.inference.handleApprovalDecision(frame.payload);
        return;
      // ── Feature 10: orchestration (§16.3) ──
      case "orchestration.release":
        await this.orchestration?.handleRelease(frame.payload as {
          releaseId: string;
          runId: string;
          workspaceGeneration: number;
          reason?: string;
        });
        return;
      case "orchestration.budget_updated":
        // §16.3 tighten-only: V1 records the frame; enforcement lands with
        // the quota loop's dispatch-allowance wiring.
        this.log.debug("orchestration.budget_updated received");
        return;
      // ── §8.7 (A4): execution.policy.update — the engine's tighten-only
      // allowance + accounted usage. Validated by the session's enforcer
      // (conversations) or the dispatch's allowance overlay (orchestration):
      // a backward usage roll or a loosening allowance is REJECTED with
      // protocol.error (§8.7 defines no dedicated rejection frame — recorded
      // deviation); an accepted update raises the enforced ceilings.
      case UnifiedMessageType.EXECUTION_POLICY_UPDATE:
        await this.handlePolicyUpdate(
          frame.payload as import("../protocol/unifiedFrames.js").ExecutionPolicyUpdatePayload,
          frame as unknown as { messageId?: string },
        );
        return;
      default:
        this.log.warn("AgentWorker: unhandled frame type", frame.type);
    }
  }

  /**
   * §8.7 (A4): apply an execution.policy.update. Orchestration dispatches
   * apply to their live allowance overlay; conversations to the session's
   * §8.7 enforcer. A rejected frame answers protocol.error INVALID_MESSAGE
   * so the engine knows the update was a violation.
   */
  private async handlePolicyUpdate(
    payload: import("../protocol/unifiedFrames.js").ExecutionPolicyUpdatePayload,
    frame?: { messageId?: string },
  ): Promise<void> {
    // Orchestration path: a dispatchId names the dispatch's allowance.
    if (payload.dispatchId && this.orchestration) {
      const verdict = this.orchestration.applyPolicyUpdate(payload);
      if (verdict === "applied") {
        this.log.info(
          `execution.policy.update applied to dispatch ${payload.dispatchId}: maxTokens=${payload.allowance?.maxTokens ?? "none"}`,
        );
        return;
      }
      if (verdict === "rejected") {
        await this.rejectPolicyUpdate(payload, frame,
          "execution.policy.update loosens the allowance (tighten-only, §8.7)");
        return;
      }
      // unknown-dispatch falls through to the conversation path.
    }
    // Conversation path: the execution's enforcer (registry-seeded).
    const enforcer = this.sessions.enforcerFor(payload.executionId);
    if (!enforcer) {
      this.log.warn(
        `execution.policy.update for unknown execution ${payload.executionId} — ignored (no open session)`,
      );
      return;
    }
    const verdict = enforcer.applyPolicyUpdate(payload.usage, payload.allowance);
    if (verdict.kind === "accepted") {
      this.log.info(
        `execution.policy.update applied for ${payload.executionId}: maxTokens=${verdict.allowanceMaxTokens ?? "none"}, accounting=${JSON.stringify(enforcer.accounting())}`,
      );
      return;
    }
    await this.rejectPolicyUpdate(payload, frame, verdict.reason);
  }

  /** §8.7 violation — reject with protocol.error (recorded deviation:
   * the design defines no dedicated rejection frame). */
  private async rejectPolicyUpdate(
    payload: { executionId: string },
    frame?: { messageId?: string },
    reason?: string,
  ): Promise<void> {
    const message = reason ?? "execution.policy.update rejected (§8.7 violation)";
    this.log.warn(
      `execution.policy.update rejected for ${payload.executionId}: ${message}`,
    );
    await this.executionSender.sendProtocolError({
      code: "INVALID_MESSAGE",
      message,
      retryable: false,
      offendingMessageId: frame?.messageId ?? null,
      scope: "EXECUTION",
      details: { executionId: payload.executionId },
    });
  }

  /**
   * `session.open` (§5.2) — establish a session: instantiate the model once,
   * register tools, store knowledge-source handles.
   */
  private async handleExecutionStart(payload: ExecutionStartPayload): Promise<void> {
    const orchestration = (payload as unknown as { orchestration?: unknown }).orchestration;
    if (orchestration !== undefined && orchestration !== null) {
      if (!this.orchestration) {
        this.log.warn(
          "orchestration execution.start received without a workspace root — failing closed",
        );
        return;
      }
      await this.orchestration.handleAssign({
        requestId: payload.executionId,
        sessionId: payload.sessionId,
        orchestration,
        assignmentDigest: (payload as unknown as { assignmentDigest?: string }).assignmentDigest,
      });
      return;
    }
    await this.inference.handleStart(payload);
  }

  /** `execution.cancel` — unified cancellation entry point. */
  private handleExecutionCancel(payload: ExecutionCancelPayload): void {
    this.orchestration?.handleCancel({
      attemptId: payload.executionId,
      requestId: payload.executionId,
    });
    this.inference.handleCancel(payload);
  }

  /**
   * `session.open` (§5.2) — establish a session: instantiate the model once,
   * register tools, store knowledge-source handles. §7.3 (Wave 6, A4): the
   * session's policy block is captured so an orchestration session's host
   * limits ride its dispatches.
   */
  private async handleSessionOpen(payload: unknown): Promise<void> {
    try {
      const open = payload as import("../protocol/unifiedFrames.js").SessionOpenPayload;
      await this.sessions.open(open);
      // §7.3 (A4): capture the session's enforced policy for the
      // orchestration executor (conversations enforce via the registry).
      if (open.policy) {
        this.orchestration?.recordSessionPolicy(open.sessionId, open.policy);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error("AgentWorker: failed to open session:", message);
    }
  }

  /**
   * `session.close` (§5.2) — tear down a session: dispose the model, drop
   * the entry.
   */
  private handleSessionClose(payload: unknown): void {
    const p = payload as { sessionId?: string };
    if (p.sessionId) {
      this.sessions.close(p.sessionId);
    }
  }
}
