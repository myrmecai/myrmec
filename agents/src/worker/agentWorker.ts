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
import type { Envelope } from "../protocol/envelope.js";
import {
  agentBindPayloadSchema,
  agentReleasePayloadSchema,
} from "../protocol/agentFrames.js";
import type { Logger } from "../models/index.js";
import { InferenceExecutor } from "../executor/InferenceExecutor.js";
import { SessionRegistry } from "../session/SessionRegistry.js";
import { ApprovalCoordinator } from "../executor/ApprovalCoordinator.js";
import type { SessionOpenPayload, InferenceAssignPayload, InferenceCancelPayload } from "../protocol/inferenceFrames.js";
import type { ChatModelFactory, SessionToolFactory } from "../executor/providers.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";

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
}

export class AgentWorker {
  private readonly sessions: SessionRegistry;
  private readonly inference: InferenceExecutor;
  private readonly log: Logger;

  constructor(options: AgentWorkerOptions) {
    this.log = options.logger ?? console;
    const send = (frame: Envelope): Promise<void> => {
      options.post({ kind: "frame", frame });
      return Promise.resolve();
    };

    // Unified Inference Dispatch (§6–§7)
    this.sessions = new SessionRegistry({
      chatModelFactory: options.chatModelFactory,
      sessionToolFactory: options.sessionToolFactory,
      logger: this.log,
    });

    // HITL approval coordinator — emits approval.request frames over the
    // worker's send sink and blocks until the matching approval.decision
    // arrives (routed by the Supervisor back to the executor's
    // handleApprovalDecision). One coordinator is shared by all in-flight
    // turns in this worker.
    const approvals = new ApprovalCoordinator({
      send,
      logger: this.log,
    });

    this.inference = new InferenceExecutor({
      registry: this.sessions,
      send,
      httpClient: options.httpClient,
      agentAccessToken: options.agentAccessToken,
      maxIterations: options.maxIterations,
      approvals,
      logger: this.log,
    });
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
      case MessageType.INFERENCE_ASSIGN:
        this.inference.handleAssign(frame.payload as InferenceAssignPayload);
        return;
      case MessageType.INFERENCE_CANCEL:
        this.inference.handleCancel(frame.payload as InferenceCancelPayload);
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
      default:
        this.log.warn("AgentWorker: unhandled frame type", frame.type);
    }
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
