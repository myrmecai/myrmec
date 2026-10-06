// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Agent: the in-isolate session body.
 *
 * This is the byte-identical session body the locked SDK design (section 9.3) places
 * below both Supervisors - it runs the task and conversation dispatchers and
 * knows nothing about where its output goes. Frames it would put on the wire
 * are handed to {@link AgentOptions.post}; the Supervisor decides whether
 * those reach the engine only (headless) or the editor too (interactive). It
 * resolves its own model per task/turn from the engine descriptor - live model
 * adapters cannot cross the thread boundary, so the worker constructs its
 * adapters there.
 */
import { MessageType as UnifiedMessageType } from "../protocol/unifiedFrames.js";
import { makeEnvelope } from "../protocol/envelope.js";
import type { Logger } from "../models/index.js";
import { InferenceExecutor } from "../executor/InferenceExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import { ApprovalCoordinator } from "../executor/ApprovalCoordinator.js";
import { ConversationEventReporter } from "../executor/ConversationEventReporter.js";
import { CaptureFilter } from "../executor/CaptureFilter.js";
import type {
  ExecutionCancelPayload,
  ExecutionStartPayload,
  ExecutionInteractionPayload,
  ExecutionControlPayload,
  ExecutionControlRequestResolvedPayload,
  OrchestrationExecutionStartPayload,
} from "../protocol/unifiedFrames.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";
import { OrchestrationExecutor } from "./OrchestrationExecutor.js";
import type { ExecutionFrameSender } from "../executor/ExecutionFrameSender.js";

export interface AgentOptions {
  /** Emit a frame back to the Supervisor for routing (the Agent's only sink). */
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
  /** Hard cap, in bytes, on a single inlined image attachment (native
   * image parts). Undefined = no size cap. Forwarded to the executor. */
  maxImageBytes?: number;
  logger?: Logger;
  /** Feature 10 (section 17.1): workspace root for orchestration runs. Required
   * for orchestration dispatches; absent -> orchestration assigns fail
   * closed with ASSIGNMENT_VALIDATION_ERROR. */
  workspaceRoot?: string;
  /** Feature 10 (section 16.3): durable outbox root. */
  outboxRoot?: string;
  /** HITL (section 17.4): the orchestration project's autoHitlOnDestructive
   * matrix input (from session.open; conservative default true). */
  autoHitlOnDestructive?: boolean;
}

export class Agent {
  private readonly sessions: SessionRegistry;
  private readonly inference: InferenceExecutor;
  private readonly orchestration: OrchestrationExecutor | null;
  /** The capture policy bound per session.open (fail-closed METADATA default). */
  private readonly eventReporter: ConversationEventReporter;
  private readonly log: Logger;
  private readonly executionSender: ExecutionFrameSender;
  /** Native image parts (design section 5.4): byte cap forwarded to the
   * InferenceExecutor; undefined = no size cap. */
  private readonly maxImageBytes?: number;

  constructor(options: AgentOptions) {
    this.log = options.logger ?? console;
    this.maxImageBytes = options.maxImageBytes;

    // Unified outbound sender: every execution.* frame the Agent produces
    // is wrapped in the legacy Envelope shape (type is the unified frame
    // family) and posted back to the Supervisor for routing.
    this.executionSender = this.buildExecutionSender(options.post);

    // Unified Inference Dispatch
    this.sessions = new SessionRegistry({
      chatModelFactory: options.chatModelFactory,
      sessionToolFactory: options.sessionToolFactory,
      logger: this.log,
    });

    // The orchestration dispatch handler - only constructed when the Host
    // configured a workspace root. Without one, an orchestration dispatch
    // fails closed (the runner never executes). The session registry is
    // the dispatch source of truth: the assignment rides session.open and
    // is installed at open; the start frame only activates it.
    this.orchestration = options.workspaceRoot
      ? new OrchestrationExecutor({
          workspaceRoot: options.workspaceRoot,
          outboxRoot: options.outboxRoot ?? `${options.workspaceRoot}/outbox`,
          chatModelFactory: options.chatModelFactory,
          sender: this.executionSender,
          sessions: this.sessions,
          autoHitlOnDestructive: options.autoHitlOnDestructive ?? true,
          logger: this.log,
        })
      : null;

    // HITL approval coordinator - emits execution.approval.requested
    // (unified) when an executionId is present, falling back to legacy
    // approval.request for the conversation path until Task 6 deletes it.
    const approvals = new ApprovalCoordinator({
      sender: this.executionSender,
      logger: this.log,
    });

    // section 8.4/section 15 rule 12: the conversation event stream - TurnExecutor
    // callbacks -> execution.event frames, each data map filtered through
    // the session's capture policy (null => METADATA, fail closed; the
    // engine always populates the block). The reporter binds to the
    // per-turn executionId inside the executor.
    const events = (this.eventReporter = new ConversationEventReporter({
      sender: this.executionSender,
      filter: new CaptureFilter(null, this.log),
      logger: this.log,
    }));

    this.inference = new InferenceExecutor({
      registry: this.sessions,
      sender: this.executionSender,
      httpClient: options.httpClient,
      agentAccessToken: options.agentAccessToken,
      maxIterations: options.maxIterations,
      maxImageBytes: this.maxImageBytes,
      approvals,
      events,
      logger: this.log,
    });
  }

  /** Build an {@link ExecutionFrameSender} that posts unified frames back
   * through the Agent's outbound sink. */
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
      // protocol 22.3/22.4/22.6/22.7 (Task 7): the coordinator's durable
      // control states + the interaction-loop's dedicated-channel frames.
      sendExecutionControlState: (p) =>
        send("execution.control.state", p as Record<string, unknown>),
      sendExecutionInteractionDelta: (p) =>
        send("execution.interaction.delta", p as Record<string, unknown>),
      sendExecutionInteractionComplete: (p) =>
        send("execution.interaction.complete", p as Record<string, unknown>),
      sendExecutionInteractionFailed: (p) =>
        send("execution.interaction.failed", p as Record<string, unknown>),
      sendExecutionControlRequest: (p) =>
        send("execution.control.request", p as Record<string, unknown>),
      sendProtocolError: (p) => send("protocol.error", p as Record<string, unknown>),
    };
  }

  /** True while an inference turn is executing. */
  get isBusy(): boolean {
    return this.inference.isBusy;
  }

  /**
   * PSK receive path (design 2026-09-16-credential-envelope-delivery.md
   * section 6/section 10): the transport invokes this with the run PSK from
   * `host.opened`; the registry keeps it in process memory only and uses it
   * to derive per-session keys for envelope unwrapping.
   */
  setPsk(psk: Uint8Array): void {
    this.sessions.setPsk(psk);
  }

  /** Dispatch an inbound message from the Supervisor. */
  async handle(message: WorkerInbound): Promise<void> {
    if (message.kind === "psk") {
      // The parent decoded the run PSK from host.opened and handed it
      // across the thread boundary; the registry keeps it in memory only
      // and uses it to derive per-session keys for envelope unwrapping.
      this.sessions.setPsk(new Uint8Array(message.psk));
      return;
    }
    if (message.kind === "ack") {
      // The engine's protocol.ack for a host->engine durable frame: the
      // executor's outbox acknowledges the matching record by messageId
      // (best-effort; an unknown id is a no-op).
      await this.orchestration?.handleEngineAck(message.acknowledgedMessageId);
      return;
    }
    if (message.kind === "connection-state") {
      // §22.8 (D7): the supervisor's session connection-state seam.
      // fatal -> stop the session runtime through the EXISTING close path
      // (model dispose, vault clear, capacity released) exactly once;
      // ready=false/true toggle the session's admission gate. Idempotent
      // for repeated notifications (an already-stopped session is a
      // registry no-op).
      this.handleConnectionState(message);
      return;
    }
    if (message.kind === "channel-opened") {
      // §7.5/§22.8 (D7 cutover, Task 11): the supervisor completed the
      // dedicated channel handshake — record the binding in the registry
      // (liveness bookkeeping; the socket stays supervisor-side).
      this.sessions.bindChannel(
        message.sessionId,
        { supervisorSide: true },
        {
          sessionId: message.sessionId,
          highestContiguousSequence: message.opened.highestContiguousSequence,
        },
      );
      return;
    }
    if (message.kind === "channel-dead") {
      // §22.8 (D7): the bound channel died — clear the registry's binding
      // mark (the fatal session teardown rides connection-state).
      this.sessions.markChannelDead(message.sessionId);
      return;
    }
    if (message.kind === "channel-unbind") {
      // Session close: drop the registry's channel binding (the socket
      // itself is closed supervisor-side).
      this.sessions.unbindChannel(message.sessionId);
      return;
    }
    if (message.kind === "cancel-execution") {
      // §13 (A2, Task 11): the CANCEL_EXECUTION resume decision IS the
      // cancellation command — the executor's existing cancel path (the
      // orchestration executor keys on executionId with the dispatchId
      // fallback; the inference executor on executionId).
      this.orchestration?.handleCancel({
        dispatchId: message.executionId,
        executionId: message.executionId,
      });
      this.inference.handleCancel({
        executionId: message.executionId,
      } as ExecutionCancelPayload);
      return;
    }
    if (message.kind === "close-retained") {
      // §13 (A2, Task 11): a CLOSE resume decision / retention expiry —
      // the EXISTING session teardown (model dispose, vault clear,
      // capacity release; idempotent registry-side).
      this.sessions.close(message.sessionId);
      return;
    }
    if (message.kind !== "envelope") {
      this.log.warn("Agent: unknown inbound message", message);
      return;
    }
    const { frame } = message;
    switch (frame.type) {
      // -- Unified Inference Dispatch (section 5) --
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
        // The verdict unblocks a handler awaiting ctx.requestApproval.
        // The durable path is the engine row; this branch covers a
        // host-authored decision frame when the engine pushes one.
        this.inference.handleApprovalDecision(frame.payload);
        return;
      // -- execution.policy.update - the engine's tighten-only
      // allowance + accounted usage. Validated by the session's enforcer
      // (conversations) or the dispatch's allowance overlay (orchestration):
      // a backward usage roll or a loosening allowance is REJECTED with
      // protocol.error (the design defines no dedicated rejection frame -
      // recorded deviation); an accepted update raises the enforced ceilings.
      case UnifiedMessageType.EXECUTION_POLICY_UPDATE:
        await this.handlePolicyUpdate(
          frame.payload as import("../protocol/unifiedFrames.js").ExecutionPolicyUpdatePayload,
          frame as unknown as { messageId?: string },
        );
        return;
      // -- protocol 22.3/22.6 (Task 7): the inbound interaction command +
      // the proposal-resolution command ride the DEDICATED Agent Channel
      // (or the control socket for unbound sessions). Both route to the
      // OrchestrationExecutor that owns the identity (executionId +
      // dispatchId); an unresolvable identity answers protocol.error
      // IDENTITY_MISMATCH (fail closed - never silently dropped).
      case UnifiedMessageType.EXECUTION_INTERACTION:
        await this.handleExecutionInteraction(
          frame.payload as ExecutionInteractionPayload,
          frame as unknown as { messageId?: string },
        );
        return;
      case UnifiedMessageType.EXECUTION_CONTROL:
        // §22.4 (Task 11 fix): one engine HOLD/CONTINUE command — the
        // executor routes it to the dispatch's coordinator (identity
        // resolved first; unresolvable answers protocol.error
        // IDENTITY_MISMATCH — fail closed, never silently dropped).
        await this.handleExecutionControl(
          frame.payload as ExecutionControlPayload,
          frame as unknown as { messageId?: string },
        );
        return;
      case UnifiedMessageType.EXECUTION_CONTROL_REQUEST_RESOLVED:
        this.handleControlRequestResolved(
          frame.payload as ExecutionControlRequestResolvedPayload,
        );
        return;
      default:
        this.log.warn("Agent: unhandled frame type", frame.type);
    }
  }

  /**
   * section 8.7 (A4): apply an execution.policy.update. Orchestration dispatches
   * apply to their live allowance overlay; conversations to the session's
   * section 8.7 enforcer. A rejected frame answers protocol.error INVALID_MESSAGE
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
          "execution.policy.update loosens the allowance (tighten-only, section 8.7)");
        return;
      }
      // unknown-dispatch falls through to the conversation path.
    }
    // Conversation path: the execution's enforcer (registry-seeded).
    const enforcer = this.sessions.enforcerFor(payload.executionId);
    if (!enforcer) {
      this.log.warn(
        `execution.policy.update for unknown execution ${payload.executionId} - ignored (no open session)`,
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

  /** section 8.7 violation - reject with protocol.error (recorded deviation:
   * the design defines no dedicated rejection frame). */
  private async rejectPolicyUpdate(
    payload: { executionId: string },
    frame?: { messageId?: string },
    reason?: string,
  ): Promise<void> {
    const message = reason ?? "execution.policy.update rejected (section 8.7 violation)";
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
   * protocol 22.6 (Task 7): route one engine-authorized chat interaction
   * to the OrchestrationExecutor that owns the identity (executionId +
   * dispatchId BOTH match the live dispatch). The executor forwards into
   * its InteractiveController; an identity that resolves to nothing (no
   * orchestration executor, no open session, no live dispatch, or a
   * dispatch whose recorded executionId disagrees) fails closed with
   * protocol.error IDENTITY_MISMATCH - the executor owns its internal
   * outcomes (CAPTURE_BLOCKED, terminal, replay...) and reports them
   * itself, so a resolvable identity emits NO protocol error here.
   */
  private async handleExecutionInteraction(
    payload: ExecutionInteractionPayload,
    frame?: { messageId?: string },
  ): Promise<void> {
    if (!this.orchestration) {
      this.log.warn(
        `execution.interaction for ${payload.executionId} with no orchestration executor - failing closed`,
      );
      await this.rejectInteractionIdentity(payload, frame);
      return;
    }
    const verdict = await this.orchestration.handleInteraction(payload);
    if (verdict !== "handled") {
      this.log.warn(
        `execution.interaction refused (${verdict}) for ${payload.executionId}/${payload.dispatchId}`,
      );
      await this.rejectInteractionIdentity(payload, frame);
    }
  }

  /**
   * protocol 22.4 (Task 11 fix): route one engine HOLD/CONTINUE control
   * command to the OrchestrationExecutor that owns the identity. The
   * executor forwards to the dispatch's coordinator (the disposition is
   * the coordinator's own durable frames); an identity that resolves to
   * nothing fails closed with protocol.error IDENTITY_MISMATCH.
   */
  private async handleExecutionControl(
    payload: ExecutionControlPayload,
    frame?: { messageId?: string },
  ): Promise<void> {
    if (!this.orchestration) {
      this.log.warn(
        `execution.control for ${payload.executionId} with no orchestration executor - failing closed`,
      );
      await this.rejectInteractionIdentity(payload, frame);
      return;
    }
    const verdict = await this.orchestration.handleControlCommand(payload);
    if (verdict !== "handled") {
      this.log.warn(
        `execution.control refused (${verdict}) for ${payload.executionId}/${payload.dispatchId}`,
      );
      await this.rejectInteractionIdentity(payload, frame);
    }
  }

  /**
   * protocol 22.7 (Task 7): route the proposal RESOLUTION to the owning
   * OrchestrationExecutor (the executor forwards to the coordinator's
   * inhibitor + the controller's proposal waits). Fire-and-forget: the
   * settle is synchronous coordinator/controller state; an unresolvable
   * identity fails closed with protocol.error IDENTITY_MISMATCH.
   */
  private handleControlRequestResolved(
    payload: ExecutionControlRequestResolvedPayload,
  ): void {
    if (!this.orchestration) {
      this.log.warn(
        `execution.control.request.resolved for ${payload.executionId} with no orchestration executor - failing closed`,
      );
      void this.rejectInteractionIdentity(payload);
      return;
    }
    const verdict = this.orchestration.settleControlRequestResolved(payload);
    if (verdict !== "settled") {
      this.log.warn(
        `execution.control.request.resolved refused (${verdict}) for ` +
          `${payload.executionId}/${payload.dispatchId}`,
      );
      void this.rejectInteractionIdentity(payload);
    }
  }

  /** The shared IDENTITY_MISMATCH protocol.error for both inbound
   * interaction-family arms (same shape as the policy-update rejection). */
  private async rejectInteractionIdentity(
    payload: { executionId: string; dispatchId?: string | null },
    frame?: { messageId?: string },
  ): Promise<void> {
    await this.executionSender.sendProtocolError({
      code: "IDENTITY_MISMATCH",
      message:
        `no live orchestration execution owns executionId=${payload.executionId}` +
        ` dispatchId=${payload.dispatchId ?? "null"}`,
      retryable: false,
      offendingMessageId: frame?.messageId ?? null,
      scope: "EXECUTION",
      details: {
        executionId: payload.executionId,
        ...(payload.dispatchId != null ? { dispatchId: payload.dispatchId } : {}),
      },
    });
  }

  /**
   * `execution.start` dispatch routing: routed by the SESSION'S INSTALLED
   * EXECUTION MODE, never by frame-payload guesswork. The assignment rode
   * `session.open` (installed by the SessionRegistry at open); the start
   * frame only activates it.
   *
   * Orchestration variant (dispatchId + assignmentDigest fields on the
   * payload): require the session to exist with executionMode
   * "ORCHESTRATION" and a non-null installed assignment; validate the
   * start frame's assignmentDigest against the installed digest (mismatch
   * fails closed with ASSIGNMENT_VALIDATION_ERROR, no accept); then hand
   * the dispatch to the OrchestrationExecutor. Everything else routes to
   * the InferenceExecutor exactly as today (conversation turns AND plain
   * workflow steps).
   */
  private async handleExecutionStart(payload: ExecutionStartPayload): Promise<void> {
    const candidate = payload as unknown as Partial<OrchestrationExecutionStartPayload>;
    if (
      candidate.dispatchId !== undefined &&
      candidate.dispatchId !== null &&
      candidate.assignmentDigest !== undefined &&
      candidate.assignmentDigest !== null
    ) {
      // The orchestration start variant. Route by the session's
      // installed execution mode (the dispatch source of truth).
      const orchestrationStart = payload as unknown as OrchestrationExecutionStartPayload;
      let session = this.sessions.get(orchestrationStart.sessionId);
      if (!session) {
        // session.open() is async (it resolves the LLM model) and may
        // still be in flight when execution.start arrives - the same
        // race the InferenceExecutor path handles. Bounded retry before
        // failing closed (a terminal without an accept would hit the
        // engine's INVALID_STATE - the execution row is still STARTING).
        this.log.debug(
          `orchestration session ${orchestrationStart.sessionId} not ready yet - waiting for session.open() to complete...`,
        );
        for (let i = 0; i < 20; i++) {
          await new Promise((resolve) => setTimeout(resolve, 500));
          session = this.sessions.get(orchestrationStart.sessionId);
          if (session) break;
        }
      }
      if (!session) {
        // Fail closed: no session means the start cannot be admitted -
        // the protocol error path reports it without an accept.
        this.log.warn(
          `orchestration start for unknown session ${orchestrationStart.sessionId} - failing closed`,
        );
        await this.executionSender.sendExecutionFailed({
          executionId: orchestrationStart.executionId,
          failedAt: new Date().toISOString(),
          error: {
            code: "SESSION_NOT_OPEN",
            message: `No active session for sessionId: ${orchestrationStart.sessionId}`,
            category: null,
            retryable: false,
            retryAfterSeconds: null,
          },
          usage: {
            modelId: null,
            inputTokens: null,
            outputTokens: null,
            durationMs: null,
          },
        });
        return;
      }
      if (session.executionMode !== "ORCHESTRATION" || !session.orchestration) {
        this.log.warn(
          `orchestration start for session ${orchestrationStart.sessionId} without an installed orchestration assignment - failing closed`,
        );
        await this.executionSender.sendExecutionFailed({
          executionId: orchestrationStart.executionId,
          failedAt: new Date().toISOString(),
          error: {
            code: "SESSION_NOT_OPEN",
            message:
              `Session ${orchestrationStart.sessionId} carries no installed orchestration assignment`,
            category: null,
            retryable: false,
            retryAfterSeconds: null,
          },
          usage: {
            modelId: null,
            inputTokens: null,
            outputTokens: null,
            durationMs: null,
          },
        });
        return;
      }
      // Digest validation: the start frame's assignmentDigest must match
      // the digest installed at session.open (fail closed on mismatch -
      // admission NOT recorded).
      if (orchestrationStart.assignmentDigest !== (session.assignmentDigest ?? "")) {
        this.log.warn(
          `orchestration start for session ${orchestrationStart.sessionId} assignmentDigest mismatch - failing closed`,
        );
        await this.executionSender.sendExecutionFailed({
          executionId: orchestrationStart.executionId,
          failedAt: new Date().toISOString(),
          error: {
            code: "ASSIGNMENT_VALIDATION_ERROR",
            message:
              `assignmentDigest mismatch: start frame carries ${orchestrationStart.assignmentDigest.slice(0, 12)}..., session installed ${(session.assignmentDigest ?? "").slice(0, 12)}...`,
            category: null,
            retryable: false,
            retryAfterSeconds: null,
          },
          usage: {
            modelId: null,
            inputTokens: null,
            outputTokens: null,
            durationMs: null,
          },
        });
        return;
      }
      if (!this.orchestration) {
        this.log.warn(
          "orchestration execution.start received without a workspace root - failing closed",
        );
        await this.executionSender.sendExecutionFailed({
          executionId: orchestrationStart.executionId,
          failedAt: new Date().toISOString(),
          error: {
            code: "ASSIGNMENT_VALIDATION_ERROR",
            message: "orchestration requires a configured workspace root",
            category: null,
            retryable: false,
            retryAfterSeconds: null,
          },
          usage: {
            modelId: null,
            inputTokens: null,
            outputTokens: null,
            durationMs: null,
          },
        });
        return;
      }
      await this.orchestration.handleStart(orchestrationStart);
      return;
    }
    await this.inference.handleStart(payload);
  }

  /** `execution.cancel` - unified cancellation entry point. */
  private handleExecutionCancel(payload: ExecutionCancelPayload): void {
    // The engine's cancel payload carries dispatchId for orchestration
    // executions; the orchestration executor keys on it (falling back to
    // executionId) while the inference executor keys on executionId.
    this.orchestration?.handleCancel({
      dispatchId: payload.dispatchId ?? payload.executionId,
      executionId: payload.executionId,
    });
    this.inference.handleCancel(payload);
  }

  /**
   * `session.open` (section 5.2) - establish a session: instantiate the model once,
   * register tools, store knowledge-source handles. section 7.3 (Wave 6, A4): the
   * session's policy block is captured so an orchestration session's host
   * limits ride its dispatches.
   */
  private async handleSessionOpen(payload: unknown): Promise<void> {
    try {
      const open = payload as import("../protocol/unifiedFrames.js").SessionOpenPayload;
      await this.sessions.open(open);
      // section 7.3 (A4): capture the session's enforced policy for the
      // orchestration executor (conversations enforce via the registry).
      if (open.policy) {
        this.orchestration?.recordSessionPolicy(open.sessionId, open.policy);
      }
      // §22.2 (Task 7): the orchestration session's interaction policy
      // block - the executor's controller composes with THESE bounds
      // (never the platform defaults when the engine published policy).
      if (open.interaction) {
        this.orchestration?.recordSessionInteraction(open.sessionId, open.interaction);
      }
      // section 7.3/section 15 rule 12: capture the session's capture block so an
      // orchestration session's progress stream rides its capture limits.
      if (open.capture) {
        this.orchestration?.recordSessionCapture(open.sessionId, open.capture);
      }
      // section 8.4/section 15 rule 12: the conversation event reporter re-binds the
      // same block - its constructor default is METADATA (fail closed)
      // until the session's real policy arrives.
      this.eventReporter?.bindCapturePolicy(open.capture ?? null);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error("Agent: failed to open session:", message);
    }
  }

  /**
   * `session.close` (section 5.2) - tear down a session: dispose the model, drop
   * the entry.
   */
  private handleSessionClose(payload: unknown): void {
    const p = payload as { sessionId?: string };
    if (p.sessionId) {
      this.sessions.close(p.sessionId);
    }
  }

  /**
   * §22.8 (D7): apply the supervisor's `connection-state` notification.
   *
   * `fatal: true` → stop the session: aborts any in-flight turn (the same
   * cooperative cancellation an engine execution.cancel drives), cancels an
   * in-flight orchestration dispatch, releases the session's timers (approval
   * waits) and capacity through the EXISTING SessionRegistry teardown
   * (`close()` — model dispose, vault clear, entry dropped; the
   * enforcers/per-execution state dies with the entry). Idempotent: a
   * repeated fatal is a registry no-op.
   *
   * `ready: false|true` → the session's admission gate (the seam Task 10's
   * reconciliation completes; until then the mark is recorded and read by
   * the registry).
   */
  private handleConnectionState(message: {
    kind: "connection-state";
    sessionId: string;
    ready: boolean;
    fatal: boolean;
  }): void {
    const { sessionId, ready, fatal } = message;
    if (fatal) {
      this.log.info(
        `connection-state FATAL for session ${sessionId} — stopping the session (§22.8 D7)`,
      );
      this.sessionFatalStop(sessionId);
      return;
    }
    // Non-fatal gate flip (control-socket loss / reopen with a live
    // channel). Unknown sessions have nothing to gate — a silent no-op.
    this.sessions.applyConnectionState(sessionId, ready, false);
    // Task 10 §22.8/§14.4: the admission gate AND the live interaction
    // runtimes ride the SAME notification — ready=false fences every
    // runtime's idle timer (no auto-resume while disconnected/reconciling);
    // ready=true is the authoritative KEEP arm (the coordinator re-arms
    // toward the SAME retained instant). A session with no runtime is a
    // no-op on both.
    this.orchestration?.setSessionConnectionReady(sessionId, ready);
  }

  /**
   * §22.8 (D7) fatal stop for one session. Reuses the EXISTING teardown
   * paths and is exact-once per session: the registry `close()` is a no-op
   * once the entry is gone (a repeated fatal cannot double-release).
   */
  private sessionFatalStop(sessionId: string): void {
    // 1. Cancel any in-flight CONVERSATION turn on the session: the
    //    executor's cooperative cancellation unwinds the model loop and
    //    emits execution.cancelled itself (no terminal frames from here -
    //    the session is dead; the engine's failure/retry policy owns the
    //    outcome).
    for (const executionId of this.inference.inFlightExecutionIdsFor(sessionId)) {
      this.inference.handleCancel({
        executionId,
        dispatchId: null,
        reasonCode: "SESSION_FATAL",
        requestedAt: new Date().toISOString(),
        gracePeriodSeconds: 0,
      });
    }
    // 2. Cancel any in-flight ORCHESTRATION dispatch on the session
    //    (cooperative cancellation at the helper-call boundaries; keyed by
    //    executionId with the dispatchId fallback).
    this.orchestration?.handleCancel({
      dispatchId: null,
      executionId: sessionId,
    });
    // 3. The EXISTING session teardown: releases the approval timers held
    //    for this session's executions, disposes the model, clears the
    //    credential vault and drops the entry (capacity released exactly
    //    once - a repeated fatal is a no-op for the gone entry).
    this.sessions.close(sessionId);
  }
}
