// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * ConversationDispatcher: the seam between the supervisor's conversational
 * frames and a turn handler.
 *
 * On `conversation.turn.assign` it resolves the model the engine routed the
 * turn to, assembles the prompt (system + pinned facts + history + the new
 * user message), and runs a handler that streams the reply back as
 * `message.delta` chunks followed by a single `message.complete`. On
 * `approval.decision` it routes the verdict to the shared
 * {@link ApprovalCoordinator}, unblocking any handler awaiting
 * `ctx.requestApproval(...)`.
 *
 * The default handler streams the model's text; a consumer can supply a custom
 * handler to gate actions on human approval mid-turn. Turns run concurrently —
 * each conversation is an independent session — and the per-id coordinator
 * keeps their approval waits isolated.
 */
import type { Logger } from "../models/index.js";
import type { Envelope } from "../protocol/envelope.js";
import {
  conversationTurnAssignPayloadSchema,
  approvalDecisionPayloadSchema,
  conversationTurnCancelPayloadSchema,
  messageDelta,
  messageComplete,
  taskCancelled,
} from "../protocol/conversationFrames.js";
import type { ConversationTurnAssignPayload } from "../protocol/conversationFrames.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import {
  ApprovalCoordinator,
  type ApprovalOutcome,
} from "./ApprovalCoordinator.js";
import type { ModelResolver } from "./TaskDispatcher.js";
import type { ChatModel, ConversationMessage, MessageContentPart } from "./types.js";
import type { EngineHttpClient, RetrievalHit } from "../transport/httpClient.js";

/** Default ceiling on image bytes the SDK will inline as a native image part
 * before degrading to a text note (`myrmec.agent.attachment.max-image-bytes`,
 * #103 Slice A). 5 MiB. */
export const DEFAULT_MAX_IMAGE_BYTES = 5_242_880;

/**
 * Everything a turn handler needs: the inbound assignment, the resolved model,
 * a raw frame sender, and the approval hook. Constructed per turn by the
 * dispatcher.
 */
export interface ConversationTurnContext {
  /** The engine's turn assignment (read-only). */
  readonly payload: ConversationTurnAssignPayload;
  readonly conversationId: string;
  readonly assistantSequenceNo: number;
  /** The model resolved for this turn. */
  readonly model: ChatModel;
  /** Aborts when the engine cancels this turn (`conversation.turn.cancel`).
   * Handlers should stop streaming and return promptly when it fires. */
  readonly signal: AbortSignal;
  /** Max bytes an image attachment may be to inline as a native image part;
   * larger images degrade to a text note (#103 Slice A). */
  readonly maxImageBytes: number;
  /** Fetch an attachment's raw bytes by its engine read path. Present only
   * when the dispatcher has an HTTP client + access token; absent ⇒ image
   * attachments fall back to metadata-only text (#103 Slice A). */
  readonly fetchAttachment?: (readContentPath: string) => Promise<Uint8Array>;
  /** Emit a raw frame back to the engine. */
  send(frame: Envelope): Promise<void>;
  /** Block until a human approves or rejects a proposed action. Rejects with
   * {@link ApprovalTimeoutError} if `timeoutMs` elapses first. */
  requestApproval(options: {
    content?: string;
    payload?: Record<string, unknown>;
    timeoutMs?: number;
    expiresAt?: string;
  }): Promise<ApprovalOutcome>;
  /** Retrieve knowledge base hits for RAG grounding.
   *
   * Agents can call this ergonomic helper during a turn to ground their
   * response with knowledge base documents. The engine returns hits ordered by
   * score (highest first) and optionally records a RETRIEVAL event for audit.
   *
   * @param knowledgeBaseId Target knowledge base UUID or code.
   * @param query Free-text retrieval query.
   * @param options Optional: topK (default 5), filters, audit context (taskId/attemptId).
   * @returns Ordered hits; empty array if nothing matches or provider fails.
   */
  retrieve(
    knowledgeBaseId: string,
    query: string,
    options?: {
      topK?: number;
      filters?: Record<string, string>;
      taskId?: string;
      attemptId?: string;
    },
  ): Promise<RetrievalHit[]>;
}

/** A turn handler produces one assistant turn, emitting deltas + a complete. */
export type ConversationTurnHandler = (
  ctx: ConversationTurnContext,
) => Promise<void>;

/** Collaborators the dispatcher needs, injected by the supervisor. */
export interface ConversationDispatcherOptions {
  send: (frame: Envelope) => Promise<void>;
  /** A fixed model adapter for every turn. Takes precedence over
   * {@link resolveModel}. */
  model?: ChatModel;
  /** Resolves a model per turn from the engine descriptor. Used when no fixed
   * {@link model} is given. */
  resolveModel?: ModelResolver;
  /** Override the default streaming handler (e.g. to gate on approval). */
  handler?: ConversationTurnHandler;
  /** HTTP client for retrieval and other RPC (required to enable ctx.retrieve). */
  httpClient?: EngineHttpClient;
  /** Current agent access token for auth on RPC calls (required for retrieve). */
  agentAccessToken?: string;
  /** Max bytes an image attachment may be to inline as a native image part
   * (#103 Slice A). Defaults to {@link DEFAULT_MAX_IMAGE_BYTES}. */
  maxImageBytes?: number;
  logger?: Logger;
}

const noopLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

export class ConversationDispatcher {
  private readonly send: (frame: Envelope) => Promise<void>;
  private readonly model?: ChatModel;
  private readonly modelResolver?: ModelResolver;
  private readonly handler: ConversationTurnHandler;
  private readonly approvals: ApprovalCoordinator;
  private readonly httpClient?: EngineHttpClient;
  private readonly agentAccessToken?: string;
  private readonly maxImageBytes: number;
  private readonly log: Logger;

  /** In-flight turns by conversationId, each with its abort controller so a
   * `conversation.turn.cancel` can stop the matching stream. */
  private readonly inFlight = new Map<string, AbortController>();

  constructor(options: ConversationDispatcherOptions) {
    if (!options.model && !options.resolveModel) {
      throw new Error(
        "ConversationDispatcher requires either a fixed `model` or a `resolveModel` factory",
      );
    }
    this.send = options.send;
    this.model = options.model;
    this.modelResolver = options.resolveModel;
    this.handler = options.handler ?? streamConversationTurn;
    this.httpClient = options.httpClient;
    this.agentAccessToken = options.agentAccessToken;
    this.maxImageBytes = options.maxImageBytes ?? DEFAULT_MAX_IMAGE_BYTES;
    this.log = options.logger ?? noopLogger;
    this.approvals = new ApprovalCoordinator({
      send: options.send,
      logger: this.log,
    });
  }

  /** In-flight approval requests awaiting a decision. */
  get pendingApprovals(): number {
    return this.approvals.pendingCount;
  }

  /** Handle an inbound `conversation.turn.assign` payload. */
  handleTurnAssign(payload: unknown): void {
    const parsed = conversationTurnAssignPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.error(
        "Malformed conversation.turn.assign payload; dropping",
        parsed.error,
      );
      return;
    }
    // Fire and forget: keep the receive loop free for approval.decision and
    // for other conversations' turns.
    void this.runTurn(parsed.data);
  }

  /** Handle an inbound `approval.decision` payload. */
  handleApprovalDecision(payload: unknown): void {
    const parsed = approvalDecisionPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.error(
        "Malformed approval.decision payload; dropping",
        parsed.error,
      );
      return;
    }
    this.approvals.resolve(parsed.data);
  }

  /** Handle an inbound `conversation.turn.cancel` payload — abort the
   * in-flight turn for the named conversation, if one is running. The turn's
   * handler observes the signal and emits `task.cancelled` itself. */
  handleTurnCancel(payload: unknown): void {
    const parsed = conversationTurnCancelPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.error(
        "Malformed conversation.turn.cancel payload; dropping",
        parsed.error,
      );
      return;
    }
    const controller = this.inFlight.get(parsed.data.conversationId);
    if (controller) {
      this.log.info(
        `Cancelling in-flight turn for conversation ${parsed.data.conversationId}`,
      );
      controller.abort();
    } else {
      this.log.debug(
        `No in-flight turn to cancel for conversation ${parsed.data.conversationId}`,
      );
    }
  }

  /** Resolve the model and run one turn through the handler. */
  private async runTurn(
    payload: ConversationTurnAssignPayload,
  ): Promise<void> {
    let model: ChatModel;
    try {
      model = await this.resolveModel(payload.model ?? undefined);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(
        `Could not resolve a model for conversation ${payload.conversationId}`,
        message,
      );
      return;
    }

    const controller = new AbortController();
    this.inFlight.set(payload.conversationId, controller);

    const ctx: ConversationTurnContext = {
      payload,
      conversationId: payload.conversationId,
      assistantSequenceNo: payload.assistantSequenceNo,
      model,
      signal: controller.signal,
      maxImageBytes: this.maxImageBytes,
      ...(this.httpClient && this.agentAccessToken
        ? {
            fetchAttachment: (readContentPath: string) =>
              this.httpClient!.fetchAttachmentContent(
                this.agentAccessToken!,
                readContentPath,
              ),
          }
        : {}),
      send: (frame) => this.send(frame),
      requestApproval: (options) =>
        this.approvals.requestApproval({
          conversationId: payload.conversationId,
          ...options,
        }),
      retrieve: async (knowledgeBaseId, query, options) => {
        if (!this.httpClient || !this.agentAccessToken) {
          throw new Error(
            "Knowledge base retrieval not configured; httpClient and agentAccessToken required",
          );
        }
        return this.httpClient.retrieve(this.agentAccessToken, {
          knowledgeBaseId,
          query,
          topK: options?.topK,
          filters: options?.filters,
          taskId: options?.taskId,
          attemptId: options?.attemptId,
        });
      },
    };

    try {
      await this.handler(ctx);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(
        `Conversation turn handler failed for ${payload.conversationId}`,
        message,
      );
    } finally {
      this.inFlight.delete(payload.conversationId);
    }
  }

  /** Pick the model for a turn: the fixed model wins, else the resolver. */
  private async resolveModel(
    info: ModelInfoWire | undefined,
  ): Promise<ChatModel> {
    if (this.model) {
      return this.model;
    }
    if (this.modelResolver) {
      return this.modelResolver(info);
    }
    // Guarded against in the constructor; unreachable in practice.
    throw new Error("No model available");
  }
}

/**
 * Default turn handler: streams the model's reply token-by-token as
 * `message.delta` frames, then emits a `message.complete` with the full text.
 * Falls back to a single non-streamed {@link ChatModel.invoke} when the
 * adapter does not implement {@link ChatModel.stream}.
 */
export async function streamConversationTurn(
  ctx: ConversationTurnContext,
): Promise<void> {
  const { payload, conversationId, assistantSequenceNo, model } = ctx;
  const messages = assembleConversationMessages(payload);
  await applyImageParts(messages, ctx);

  let fullText = "";
  let deltaIndex = 0;
  let tokenCount: number | undefined;

  const emitDelta = (content: string) =>
    ctx.send(
      messageDelta({
        conversationId,
        sequenceNo: assistantSequenceNo,
        deltaIndex: deltaIndex++,
        content,
      }),
    );

  const emitCancelled = () =>
    ctx.send(
      taskCancelled({
        conversationId,
        sequenceNo: assistantSequenceNo,
        partialContent: fullText,
      }),
    );

  // Already cancelled before the model produced anything.
  if (ctx.signal.aborted) {
    await emitCancelled();
    return;
  }

  if (typeof model.stream === "function") {
    for await (const chunk of model.stream(messages, [])) {
      if (ctx.signal.aborted) {
        await emitCancelled();
        return;
      }
      if (chunk.content) {
        fullText += chunk.content;
        await emitDelta(chunk.content);
      }
      const tokens = chunk.usage?.completionTokens ?? chunk.usage?.totalTokens;
      if (tokens !== undefined) {
        tokenCount = tokens;
      }
    }
  } else {
    const reply = await model.invoke(messages, []);
    if (ctx.signal.aborted) {
      await emitCancelled();
      return;
    }
    fullText = reply.content ?? "";
    if (fullText.length > 0) {
      await emitDelta(fullText);
    }
    tokenCount = reply.usage?.completionTokens ?? reply.usage?.totalTokens;
  }

  if (ctx.signal.aborted) {
    await emitCancelled();
    return;
  }

  await ctx.send(
    messageComplete({
      conversationId,
      sequenceNo: assistantSequenceNo,
      content: fullText,
      ...(modelCodeOf(payload) !== undefined
        ? { modelCode: modelCodeOf(payload) }
        : {}),
      ...(tokenCount !== undefined ? { tokenCount } : {}),
    }),
  );
}

/** Assemble the LLM transcript from a turn assignment: a combined system
 * message (prompt + pinned facts), the sliding-window history, then the new
 * user message. */
export function assembleConversationMessages(
  payload: ConversationTurnAssignPayload,
): ConversationMessage[] {
  const messages: ConversationMessage[] = [];

  const system = [payload.systemPrompt, payload.pinnedFacts]
    .filter((s): s is string => typeof s === "string" && s.length > 0)
    .join("\n\n");
  if (system.length > 0) {
    messages.push({ role: "system", content: system });
  }

  for (const entry of payload.history) {
    messages.push({ role: roleOf(entry.role), content: entry.content });
  }

  const attachmentContext = buildAttachmentContext(payload.attachments);
  const userContent = attachmentContext.length > 0
    ? `${payload.userMessage}\n\n${attachmentContext}`
    : payload.userMessage;
  messages.push({ role: "user", content: userContent });
  return messages;
}

/** Render bound attachments (#103) into a text block appended to the user
 * message. Text documents extracted inline by the engine are embedded
 * verbatim; images and large/binary files are summarised as metadata notes
 * the model can reason about (and fetch on demand by id). */
export function buildAttachmentContext(
  attachments: ConversationTurnAssignPayload["attachments"] | undefined,
): string {
  if (!attachments || attachments.length === 0) {
    return "";
  }
  const blocks: string[] = [];
  for (const a of attachments) {
    if (typeof a.inlineText === "string" && a.inlineText.length > 0) {
      blocks.push(
        `--- Attached file: ${a.filename} (${a.mediaType}) ---\n${a.inlineText}`,
      );
    } else if (a.image) {
      blocks.push(
        `--- Attached image: ${a.filename} (${a.mediaType}, ${a.sizeBytes} bytes) ---`,
      );
    } else {
      blocks.push(
        `--- Attached file: ${a.filename} (${a.mediaType}, ${a.sizeBytes} bytes; ` +
          `content not inlined, fetch by id ${a.id} if needed) ---`,
      );
    }
  }
  return blocks.join("\n\n");
}

/**
 * #103 Slice A — turn vision-flagged image attachments into native multimodal
 * image parts on the latest user message.
 *
 * For each attachment the engine flagged `image` (i.e. the resolved model is
 * vision-capable) with a `readContentPath`, fetch the raw bytes and append an
 * `image_url` data-URL part. Oversized images and fetch failures degrade to a
 * short text note so the turn still runs. With no fetcher configured (no HTTP
 * client/token) the turn keeps the metadata-only text block that
 * {@link buildAttachmentContext} already appended.
 */
async function applyImageParts(
  messages: ConversationMessage[],
  ctx: ConversationTurnContext,
): Promise<void> {
  if (!ctx.fetchAttachment) {
    return;
  }
  const parts: MessageContentPart[] = [];
  const notes: string[] = [];
  for (const a of ctx.payload.attachments) {
    if (a.image !== true) {
      continue;
    }
    const path = a.readContentPath;
    if (typeof path !== "string" || path.length === 0) {
      continue;
    }
    try {
      const bytes = await ctx.fetchAttachment(path);
      if (bytes.length > ctx.maxImageBytes) {
        notes.push(`[image '${a.filename}' too large to inline]`);
        continue;
      }
      const base64 = Buffer.from(bytes).toString("base64");
      parts.push({
        type: "image_url",
        image_url: { url: `data:${a.mediaType};base64,${base64}` },
      });
    } catch {
      notes.push(`[image '${a.filename}' unavailable]`);
    }
  }

  if (parts.length === 0 && notes.length === 0) {
    return;
  }
  const last = messages[messages.length - 1];
  if (!last || last.role !== "user") {
    return;
  }
  const existingText = typeof last.content === "string" ? last.content : "";
  const text =
    notes.length > 0 ? `${existingText}\n\n${notes.join("\n")}` : existingText;
  last.content = parts.length > 0 ? [{ type: "text", text }, ...parts] : text;
}

/** Map an engine history role (USER / ASSISTANT / SYSTEM) to a transcript
 * role; unknown roles fall back to `user`. */
function roleOf(role: string): ConversationMessage["role"] {
  switch (role.toUpperCase()) {
    case "ASSISTANT":
      return "assistant";
    case "SYSTEM":
      return "system";
    case "USER":
    default:
      return "user";
  }
}

/** `provider/modelId` for the `message.complete` modelCode, when the engine
 * attached model info to the turn. */
function modelCodeOf(payload: ConversationTurnAssignPayload): string | undefined {
  const info = payload.model;
  if (!info) {
    return undefined;
  }
  return `${info.provider}/${info.modelId}`;
}
