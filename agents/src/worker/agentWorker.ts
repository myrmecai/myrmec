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
import {
  TaskDispatcher,
  ConversationDispatcher,
  type ModelResolver,
} from "../executor/index.js";
import type { Tool } from "../executor/types.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import { resolveChatModel } from "../models/resolveModel.js";
import type { WorkerInbound, WorkerOutbound } from "./agentWorkerProtocol.js";

export interface AgentWorkerOptions {
  /** Emit a frame back to the Supervisor for routing (the worker's only sink). */
  post: (message: WorkerOutbound) => void;
  /** Resolve a model per task/turn from the engine descriptor. Defaults to the
   * built-in provider resolver (OpenAI-compatible, Anthropic, Google). */
  resolveModel?: ModelResolver;
  /** Tools the worker can offer; constructed in-isolate (a later registry
   * slice fills this in). Defaults to none. */
  tools?: Tool[];
  /** Iteration cap forwarded to the executor. */
  maxIterations?: number;
  /** Max bytes an image attachment may be to inline as a native image part
   * (#103 Slice A). */
  maxImageBytes?: number;
  /** HTTP client for engine RPC (retrieval, etc.). Optional. */
  httpClient?: EngineHttpClient;
  /** Current agent access token for auth on RPC calls. Optional. */
  agentAccessToken?: string;
  logger?: Logger;
}

export class AgentWorker {
  private readonly tasks: TaskDispatcher;
  private readonly conversations: ConversationDispatcher;
  private readonly log: Logger;

  constructor(options: AgentWorkerOptions) {
    this.log = options.logger ?? console;
    const send = (frame: Envelope): Promise<void> => {
      options.post({ kind: "frame", frame });
      return Promise.resolve();
    };
    const resolveModel: ModelResolver = options.resolveModel ?? resolveChatModel;

    this.tasks = new TaskDispatcher({
      send,
      resolveModel,
      tools: options.tools,
      maxIterations: options.maxIterations,
      logger: this.log,
    });
    this.conversations = new ConversationDispatcher({
      send,
      resolveModel,
      httpClient: options.httpClient,
      agentAccessToken: options.agentAccessToken,
      ...(options.maxImageBytes !== undefined
        ? { maxImageBytes: options.maxImageBytes }
        : {}),
      logger: this.log,
    });
  }

  /** True while a workflow task is executing. */
  get isBusy(): boolean {
    return this.tasks.isBusy;
  }

  /** In-flight approval requests awaiting a decision. */
  get pendingApprovals(): number {
    return this.conversations.pendingApprovals;
  }

  /** Dispatch an inbound message from the Supervisor. */
  handle(message: WorkerInbound): void {
    if (message.kind !== "envelope") {
      this.log.warn("AgentWorker: unknown inbound message", message);
      return;
    }
    const { frame } = message;
    switch (frame.type) {
      case MessageType.TASK_ASSIGN:
        this.tasks.handleAssign(frame.payload);
        return;
      case MessageType.TASK_CANCEL:
        this.tasks.handleCancel(frame.payload);
        return;
      case MessageType.CONVERSATION_TURN_ASSIGN:
        this.conversations.handleTurnAssign(frame.payload);
        return;
      case MessageType.CONVERSATION_TURN_CANCEL:
        this.conversations.handleTurnCancel(frame.payload);
        return;
      case MessageType.APPROVAL_DECISION:
        this.conversations.handleApprovalDecision(frame.payload);
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
