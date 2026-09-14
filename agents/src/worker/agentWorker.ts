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
import { MessageType } from "../protocol/messages.js";
import { makeEnvelope } from "../protocol/envelope.js";
import {
  agentBindPayloadSchema,
  agentReleasePayloadSchema,
} from "../protocol/agentFrames.js";
import type { Logger } from "../models/index.js";
import { InferenceExecutor } from "../executor/InferenceExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import { ApprovalCoordinator } from "../executor/ApprovalCoordinator.js";
import type {
  SessionOpenPayload,
  InferenceAssignPayload,
  InferenceCancelPayload,
} from "../protocol/inferenceFrames.js";
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
        post({ kind: "frame", frame: makeEnvelope(type as MessageType, payload) });
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
    };
  }

  /** True while an inference turn is executing. */
  get isBusy(): boolean {
    return this.inference.isBusy;
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
      case MessageType.SESSION_OPEN:
        await this.handleSessionOpen(frame.payload);
        return;
      case MessageType.SESSION_CLOSE:
        this.handleSessionClose(frame.payload);
        return;
      case "execution.start":
        await this.handleExecutionStart(frame.payload as ExecutionStartPayload);
        return;
      case "execution.cancel":
        this.handleExecutionCancel(frame.payload as ExecutionCancelPayload);
        return;
      // Legacy inference frames are still accepted inbound until the
      // engine cutover lands (Tasks 4–5), but outbound emissions are
      // unified execution.* frames.
      case MessageType.INFERENCE_ASSIGN:
        await this.handleInferenceAssign(frame.payload as InferenceAssignPayload);
        return;
      case MessageType.INFERENCE_CANCEL:
        this.handleInferenceCancel(frame.payload as InferenceCancelPayload);
        return;
      case MessageType.APPROVAL_DECISION:
        // Approval decisions are routed to the InferenceExecutor's
        // internal ApprovalCoordinator.
        this.inference.handleApprovalDecision(frame.payload);
        return;
      case MessageType.AGENT_BIND:
        this.handleBind(frame.payload);
        return;
      case MessageType.AGENT_RELEASE:
        this.handleRelease(frame.payload);
        return;
      // ── Feature 10: orchestration (§16.3) ──
      case MessageType.ORCHESTRATION_RELEASE:
        await this.orchestration?.handleRelease(frame.payload as {
          releaseId: string;
          runId: string;
          workspaceGeneration: number;
          reason?: string;
        });
        return;
      case MessageType.ORCHESTRATION_BUDGET_UPDATED:
        // §16.3 tighten-only: V1 records the frame; enforcement lands with
        // the quota loop's dispatch-allowance wiring.
        this.log.debug("orchestration.budget_updated received");
        return;
      default:
        this.log.warn("AgentWorker: unhandled frame type", frame.type);
    }
  }

  /** Map a legacy `inference.assign` payload to a unified `ExecutionStartPayload`. */
  private legacyAssignToExecutionStart(payload: InferenceAssignPayload): ExecutionStartPayload {
    return {
      executionId: payload.requestId,
      sessionId: payload.sessionId,
      sequenceNo: payload.response.sequenceNo,
      requestId: payload.requestId,
      deadline: new Date(Date.now() + 300_000).toISOString(),
      input: {
        messages: payload.messages.map((m) => ({
          role: m.role,
          content: m.content ?? null,
          parts: m.parts?.map((p) => ({
            type: p.type,
            text: p.text ?? null,
            attachmentId: p.attachmentId ?? null,
            mediaType: p.mediaType ?? null,
            readContentPath: p.readContentPath ?? null,
          })) ?? null,
          toolCalls: m.toolCalls?.map((tc) => ({
            id: tc.id,
            name: tc.name,
            args: tc.args,
          })) ?? null,
        })),
        attachments: null,
        conversationContinuation: null,
      },
      toolPolicy: {
        activeToolNames: payload.activeToolNames,
        approvalMode: null,
      },
      output: {
        stream: payload.stream,
        responseSequenceNo: payload.response.sequenceNo,
        format: "TEXT",
      },
    };
  }

  /** `execution.start` — unified dispatch entry point. */
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
   * `inference.assign` — legacy dispatch. An orchestration payload routes to
   * the orchestration executor (§16.3); ordinary inference is mapped onto
   * the unified `execution.start` path.
   */
  private async handleInferenceAssign(payload: InferenceAssignPayload): Promise<void> {
    await this.handleExecutionStart(this.legacyAssignToExecutionStart(payload));
  }

  /** `inference.cancel` — legacy cancellation mapped onto `execution.cancel`. */
  private handleInferenceCancel(payload: InferenceCancelPayload): void {
    this.handleExecutionCancel({
      executionId: payload.requestId,
      dispatchId: payload.requestId,
      reasonCode: "USER_REQUEST",
      requestedAt: new Date().toISOString(),
      gracePeriodSeconds: 5,
    });
  }

  /**
   * `session.open` (§5.2) — establish a session: instantiate the model once,
   * register tools, store knowledge-source handles.
   */
  private async handleSessionOpen(payload: unknown): Promise<void> {
    try {
      await this.sessions.open(payload as SessionOpenPayload);
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

  /**
   * `agent.bind` — the engine reserved this worker for a conversation.
   * Slice 3b validates + records the binding at the dispatch seam; acting
   * on it (attaching the dedicated conversation socket) lands with node
   * addressing in a later slice.
   */
  private handleBind(payload: unknown): void {
    const parsed = agentBindPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.warn("AgentWorker: invalid agent.bind payload", parsed.error.issues);
      return;
    }
    this.log.debug(
      "AgentWorker: bound to conversation",
      parsed.data.conversationId,
      "profileVersion",
      parsed.data.profileVersionId,
    );
  }

  /**
   * `agent.release` — the engine tore this worker's binding down. Slice 3b
   * validates + logs; dropping conversation state lands with the dedicated
   * conversation socket in a later slice.
   */
  private handleRelease(payload: unknown): void {
    const parsed = agentReleasePayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.warn("AgentWorker: invalid agent.release payload", parsed.error.issues);
      return;
    }
    this.log.debug(
      "AgentWorker: released from conversation",
      parsed.data.conversationId,
      parsed.data.reason ?? "",
    );
  }
}
