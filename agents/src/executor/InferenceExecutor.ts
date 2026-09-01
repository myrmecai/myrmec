// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * InferenceExecutor: handles `inference.assign` frames for the unified
 * inference dispatch protocol (T11).
 *
 * The engine sends an `inference.assign` frame when it wants the agent to run
 * one model turn inside an already-open {@link Session} (opened via
 * `session.open` and tracked by {@link SessionRegistry}). The executor:
 *
 *   1. Looks up the session; on miss it emits `inference.failed`
 *      (`SESSION_NOT_OPEN`).
 *   2. Emits `inference.accept`.
 *   3. Converts the wire {@link InferenceMessage}s to internal
 *      {@link ConversationMessage}s.
 *   4. Selects the active tools from `session.tools` by `activeToolNames`.
 *   5. Streams (`inference.delta` per chunk) or invokes the model once.
 *   6. On tool calls: emits `inference.tool_call`, runs the tool, emits
 *      `inference.tool_result`, appends the result, and loops back to the
 *      model (up to `maxIterations`).
 *   7. On cancellation: emits `inference.cancelled` with partial content.
 *   8. Emits `inference.complete` (content + tokenCount) or `inference.failed`.
 *
 * The tool loop reuses the pattern from {@link TurnExecutor.runTool}:
 * unknown tools and thrown errors are recorded as tool errors and fed back
 * to the model rather than aborting the turn.
 */
import type { Logger } from "../models/index.js";
import type { Envelope } from "../protocol/envelope.js";
import { makeEnvelope } from "../protocol/envelope.js";
import { MessageType } from "../protocol/messages.js";
import type {
  InferenceAssignPayload,
  InferenceCancelPayload,
  InferenceMessage,
} from "../protocol/inferenceFrames.js";
import type { SessionRegistry, Session } from "../session/SessionRegistry.js";
import type {
  CancellationSignal,
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  ToolSpec,
  SessionTool,
  RiskClass,
} from "./types.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import type { ApprovalCoordinator } from "./ApprovalCoordinator.js";

/** Constructor options for {@link InferenceExecutor}. */
export interface InferenceExecutorOptions {
  /** Session lookup — must be the same registry the supervisor feeds
   * `session.open`/`session.close` into. */
  registry: SessionRegistry;
  /** Outbound frame sender (the supervisor's transport). */
  send: (frame: Envelope) => Promise<void>;
  /** HTTP client used to fetch image attachment bytes. Optional; when absent,
   * image parts degrade to text-only content. */
  httpClient?: EngineHttpClient;
  /** Agent access token authorising {@link httpClient} calls. */
  agentAccessToken?: string;
  /** Hard cap on model↔tool iterations (default 25, matching TurnExecutor). */
  maxIterations?: number;
  /** Shared HITL approval coordinator; when supplied the tool loop can gate
   * DESTRUCTIVE/IRREVERSIBLE tools on human approval. Optional. */
  approvals?: ApprovalCoordinator;
  /** Logger; defaults to a no-op logger. */
  logger?: Logger;
}

const noopLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

/** Default iteration cap (mirrors TurnExecutor / the Python SDK). */
const DEFAULT_MAX_ITERATIONS = 25;

/**
 * Handles `inference.assign` and `inference.cancel` frames for open sessions.
 *
 * One executor is shared by every in-flight inference request; per-request
 * state (abort controller, partial content) is keyed by `requestId` so
 * concurrent requests stay isolated.
 */
export class InferenceExecutor {
  private readonly registry: SessionRegistry;
  private readonly send: (frame: Envelope) => Promise<void>;
  private readonly httpClient?: EngineHttpClient;
  private readonly agentAccessToken?: string;
  private readonly maxIterations: number;
  private readonly approvals?: ApprovalCoordinator;
  private readonly log: Logger;

  /** In-flight requests keyed by `requestId`, each with its abort controller
   * so an `inference.cancel` can stop the matching turn. */
  private readonly inFlight = new Map<
    string,
    { controller: AbortController; partialContent: string }
  >();

  constructor(options: InferenceExecutorOptions) {
    this.registry = options.registry;
    this.send = options.send;
    this.httpClient = options.httpClient;
    this.agentAccessToken = options.agentAccessToken;
    this.maxIterations = options.maxIterations ?? DEFAULT_MAX_ITERATIONS;
    this.approvals = options.approvals;
    this.log = options.logger ?? noopLogger;
  }

  /**
   * Retrieve knowledge-source hits for RAG grounding (§7.3).
   * Available to both service types via the executor's turn context.
   */
  async retrieve(
    knowledgeSourceId: string,
    query: string,
    options?: { topK?: number; filters?: Record<string, string>; taskId?: string; attemptId?: string },
  ): Promise<unknown[]> {
    if (!this.httpClient || !this.agentAccessToken) {
      return [];
    }
    try {
      return await this.httpClient.retrieve(this.agentAccessToken, {
        knowledgeSourceId,
        query,
        ...options,
      });
    } catch (err) {
      this.log.warn("Retrieval failed:", err instanceof Error ? err.message : String(err));
      return [];
    }
  }

  /** Number of in-flight inference requests. */
  get inFlightCount(): number {
    return this.inFlight.size;
  }

  /** True while at least one inference turn is executing. */
  get isBusy(): boolean {
    return this.inFlight.size > 0;
  }

  /**
   * Main handler for an `inference.assign` frame.
   *
   * Flow: session lookup → `inference.accept` → convert messages → select
   * tools → stream/invoke → tool loop → `inference.complete`/`inference.failed`.
   * On cancellation, emits `inference.cancelled` with whatever partial content
   * was accumulated.
   */
  async handleAssign(payload: InferenceAssignPayload): Promise<void> {
    const { requestId, sessionId } = payload;

    // 1. Session lookup — with retry because session.open() is async
    //    (it resolves the LLM model) and may still be in-flight when
    //    inference.assign arrives. Wait up to 10s for it to appear.
    let session = this.registry.get(sessionId);
    if (!session) {
      this.log.debug(`Session ${sessionId} not ready yet — waiting for session.open() to complete...`);
      for (let i = 0; i < 20; i++) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        session = this.registry.get(sessionId);
        if (session) break;
      }
    }
    if (!session) {
      this.log.warn(`No active session for sessionId: ${sessionId}`);
      await this.emitFailed(
        requestId,
        sessionId,
        "SESSION_NOT_OPEN",
        `No active session for sessionId: ${sessionId}`,
      );
      return;
    }

    // Register the in-flight turn for cancellation.
    const controller = new AbortController();
    const state = { controller, partialContent: "" };
    this.inFlight.set(requestId, state);

    try {
      // 2. Emit inference.accept.
      await this.emitAccept(requestId, sessionId);

      // 3. Convert wire messages to internal transcript.
      const messages = this.toConversationMessages(payload.messages);

      // 4. Select active tools from the session's tool map.
      const tools = this.selectTools(session, payload.activeToolNames);
      const toolSpecs: ToolSpec[] = tools.map(({ name, description, parameters }) => ({
        name,
        description,
        parameters,
      }));

      this.log.info(
        `Inference ${requestId}: ${toolSpecs.length} active tools (${toolSpecs.map(t => t.name).join(', ') || 'none'}), streaming=${payload.stream}`
      );

      const cancellation: CancellationSignal = {
        get cancelled() {
          return controller.signal.aborted;
        },
      };

      // 5/6. Run the model↔tool loop (streaming or single-shot).
      const result = await this.runToolLoop(
        session,
        messages,
        tools,
        toolSpecs,
        cancellation,
        payload,
        state,
      );

      // 7. Cancellation takes precedence over completion.
      if (cancellation.cancelled) {
        await this.emitCancelled(
          requestId,
          sessionId,
          payload.response.sequenceNo,
          state.partialContent,
        );
        return;
      }

      // 8. Emit inference.complete (or inference.failed on error).
      if (result.kind === "complete") {
        await this.emitComplete(
          requestId,
          sessionId,
          payload.response.sequenceNo,
          result.content,
          result.tokenCount,
          result.modelCode,
        );
      } else {
        await this.emitFailed(
          requestId,
          sessionId,
          result.errorCode,
          result.message,
        );
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(`Inference turn failed for request ${requestId}:`, message);
      await this.emitFailed(
        requestId,
        sessionId,
        "PROVIDER_ERROR",
        message,
      );
    } finally {
      this.inFlight.delete(requestId);
    }
  }

  /**
   * Handle an `inference.cancel` frame: abort the matching in-flight turn.
   * The main loop in {@link handleAssign} observes the abort and emits
   * `inference.cancelled` with the partial content accumulated so far.
   */
  handleCancel(payload: InferenceCancelPayload): void {
    const state = this.inFlight.get(payload.requestId);
    if (!state) {
      this.log.debug(
        `No in-flight inference request to cancel for requestId: ${payload.requestId}`,
      );
      return;
    }
    this.log.info(`Cancelling in-flight inference request ${payload.requestId}`);
    state.controller.abort();
  }

  /**
   * Handle an `approval.decision` frame: route the verdict to the
   * {@link ApprovalCoordinator}, unblocking any handler awaiting
   * `ctx.requestApproval(...)`.
   */
  handleApprovalDecision(payload: unknown): void {
    if (!this.approvals) {
      this.log.debug("InferenceExecutor: approval decision received but no coordinator wired");
      return;
    }
    this.approvals.resolve(payload as never);
  }

  // ────────────────────────── helpers ──────────────────────────

  /**
   * Map the wire {@link InferenceMessage} format to the internal
   * {@link ConversationMessage} format.
   *
   * `InferenceMessage` carries `{ role, content, parts?, toolCalls? }`;
   * `ConversationMessage` carries `{ role, content, toolCalls?, toolCallId? }`.
   * Text content passes through directly. Image `parts` are noted via their
   * `readContentPath` for later byte fetching — for now we pass text content
   * through and leave image inlining as a future optimisation (the
   * {@link ConversationDispatcher} already does native image inlining on the
   * conversation path).
   */
  private toConversationMessages(
    messages: InferenceMessage[],
  ): ConversationMessage[] {
    const out: ConversationMessage[] = [];
    for (const m of messages) {
      const content = m.content ?? "";
      const toolCalls: ModelToolCall[] | undefined = m.toolCalls?.length
        ? m.toolCalls.map((tc) => ({
            id: tc.id,
            name: tc.name,
            args: tc.args,
          }))
        : undefined;

      // `tool`-role messages echo the toolCallId they answer.
      const toolCallId =
        m.role === "tool" ? this.toolCallIdFor(m) : undefined;

      out.push({
        role: m.role,
        content,
        ...(toolCalls ? { toolCalls } : {}),
        ...(toolCallId ? { toolCallId } : {}),
      });
    }
    return out;
  }

  /**
   * Extract the `toolCallId` a tool-role message answers. The wire format
   * does not currently carry a dedicated field for this on `InferenceMessage`,
   * so we leave it undefined and let the tool loop stamp it when it appends
   * the tool result itself.
   */
  private toolCallIdFor(_m: InferenceMessage): string | undefined {
    return undefined;
  }

  /** Select the active tools from the session tool map by name. Unknown
   * names are dropped (the engine should only send authorised names). */
  private selectTools(session: Session, names: string[]): SessionTool[] {
    const tools: SessionTool[] = [];
    for (const name of names) {
      const tool = session.tools.get(name);
      if (!tool) {
        this.log.warn(
          `Session ${session.sessionId}: active tool '${name}' has no implementation; skipping`,
        );
        continue;
      }
      tools.push(tool);
    }
    return tools;
  }

  /**
   * The model↔tool iteration loop. Reuses the {@link TurnExecutor.runTool}
   * pattern: invoke (or stream) the model, execute any tool calls, feed the
   * results back, and repeat until a final answer or the iteration cap.
   *
   * Streaming vs single-shot is controlled by `payload.stream`. In streaming
   * mode each chunk is emitted as an `inference.delta` frame and the full
   * text is accumulated; tool calls are only acted on once the stream
   * completes (the stream yields content deltas, the final invoke pass
   * returns tool calls).
   */
  private async runToolLoop(
    session: Session,
    messages: ConversationMessage[],
    tools: SessionTool[],
    toolSpecs: ToolSpec[],
    cancellation: CancellationSignal,
    payload: InferenceAssignPayload,
    state: { controller: AbortController; partialContent: string },
  ): Promise<
    | { kind: "complete"; content: string; tokenCount?: number; modelCode?: string }
    | { kind: "failed"; errorCode: string; message: string }
  > {
    const { requestId, sessionId } = payload;
    const conversationId = payload.requestId;
    const toolMap = new Map<string, SessionTool>(tools.map((t) => [t.name, t]));
    const model = session.model;

    let iteration = 0;
    while (iteration < this.maxIterations) {
      if (cancellation.cancelled) {
        this.log.info("Inference cancelled; stopping tool loop");
        return {
          kind: "complete",
          content: state.partialContent,
        };
      }
      iteration += 1;
      this.log.debug(`Inference iteration ${iteration} for request ${requestId}`);

      let response: ModelResponse;
      try {
        if (payload.stream && typeof model.stream === "function") {
          response = await this.streamModel(
            model,
            messages,
            toolSpecs,
            payload,
            state,
            cancellation,
          );
        } else {
          response = await model.invoke(messages, toolSpecs);
        }
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        this.log.error("Model invocation failed:", message);
        return { kind: "failed", errorCode: "PROVIDER_ERROR", message };
      }

      if (cancellation.cancelled) {
        return { kind: "complete", content: state.partialContent };
      }

      const requested = response.toolCalls ?? [];
      if (requested.length === 0) {
        // Final answer.
        this.log.info("Model returned final response");
        const tokenCount =
          response.usage?.completionTokens ?? response.usage?.totalTokens;
        return {
          kind: "complete",
          content: response.content ?? state.partialContent,
          ...(tokenCount !== undefined ? { tokenCount } : {}),
        };
      }

      // Record the assistant turn (with its tool calls) before executing them.
      messages.push({
        role: "assistant",
        content: response.content ?? "",
        toolCalls: requested,
      });

      // Execute each requested tool call, emitting frames and feeding the
      // results back into the transcript.
      for (const call of requested) {
        if (cancellation.cancelled) {
          break;
        }

        // HITL risk gate (UC-014 §A4a): before executing a DESTRUCTIVE or
        // IRREVERSIBLE tool on a project with autoHitlOnDestructive=true,
        // emit an approval.request and block until the human decides.
        // If no ApprovalCoordinator is wired, or the project doesn't require
        // HITL, or the tool is SAFE, execute immediately (backward compatible).
        const sessionTool = toolMap.get(call.name);
        const riskClass: RiskClass = sessionTool?.riskClass ?? "SAFE";
        const needsApproval =
          session.autoHitlOnDestructive &&
          (riskClass === "DESTRUCTIVE" || riskClass === "IRREVERSIBLE") &&
          this.approvals !== undefined;

        if (needsApproval) {
          this.log.info(
            `Tool ${call.name} is ${riskClass} and project has autoHitlOnDestructive — requesting approval`,
          );
          await this.emitToolCall(requestId, sessionId, call.id, call.name, call.args);

          let outcome;
          try {
            outcome = await this.approvals!.requestApproval({
              conversationId,
              content: `Approve ${call.name}?`,
              payload: { toolName: call.name, args: call.args },
            });
          } catch (err) {
            // ApprovalTimeoutError or send failure — treat as rejection.
            this.log.warn(
              `Approval request for ${call.name} failed: ${err instanceof Error ? err.message : String(err)}`,
            );
            const rejectContent = `Tool '${call.name}' was not approved: approval request failed.`;
            await this.emitToolResult(
              requestId, sessionId, call.id, rejectContent, true,
            );
            messages.push({ role: "tool", content: rejectContent, toolCallId: call.id });
            continue;
          }

          if (!outcome.approved) {
            const rejectContent = outcome.comment
              ? `Tool '${call.name}' was rejected by a human: ${outcome.comment}`
              : `Tool '${call.name}' was rejected by a human.`;
            this.log.info(`Approval for ${call.name} rejected — skipping tool execution`);
            await this.emitToolResult(
              requestId, sessionId, call.id, rejectContent, true,
            );
            messages.push({ role: "tool", content: rejectContent, toolCallId: call.id });
            continue;
          }

          this.log.info(`Approval for ${call.name} approved — proceeding with tool execution`);
        }

        await this.emitToolCall(requestId, sessionId, call.id, call.name, call.args);

        const result = await this.runTool(call, toolMap);
        await this.emitToolResult(
          requestId,
          sessionId,
          call.id,
          result.content,
          result.isError,
        );

        messages.push({
          role: "tool",
          content: result.content,
          toolCallId: call.id,
        });
      }
    }

    // Iteration cap hit without a final answer.
    this.log.warn(`Max iterations (${this.maxIterations}) reached for ${requestId}`);
    return {
      kind: "failed",
      errorCode: "MAX_ITERATIONS",
      message: `Inference did not converge within ${this.maxIterations} iterations`,
    };
  }

  /**
   * Stream the model reply, emitting `inference.delta` per chunk and
   * accumulating the full text on `state.partialContent`. Returns the final
   * {@link ModelResponse}; tool calls (when the adapter reports them on the
   * last chunk) are surfaced via the returned object so the tool loop can
   * act on them.
   */
  private async streamModel(
    model: ChatModel,
    messages: ConversationMessage[],
    toolSpecs: ToolSpec[],
    payload: InferenceAssignPayload,
    state: { controller: AbortController; partialContent: string },
    cancellation: CancellationSignal,
  ): Promise<ModelResponse> {
    const { requestId, sessionId } = payload;
    let deltaIndex = 0;
    let fullContent = "";
    let tokenCount: number | undefined;
    let toolCalls: ModelToolCall[] | undefined;

    if (!model.stream) {
      // Adapter doesn't support streaming — fall back to invoke.
      const response = await model.invoke(messages, toolSpecs);
      return response;
    }
    for await (const chunk of model.stream(messages, toolSpecs) as AsyncIterable<ModelStreamChunk>) {
      if (cancellation.cancelled) {
        break;
      }
      if (chunk.content) {
        fullContent += chunk.content;
        state.partialContent = fullContent;
        await this.emitDelta(
          requestId,
          sessionId,
          payload.response.sequenceNo,
          deltaIndex++,
          chunk.content,
        );
      }
      if (chunk.toolCalls && chunk.toolCalls.length > 0) {
        toolCalls = chunk.toolCalls;
      }
      const tokens = chunk.usage?.completionTokens ?? chunk.usage?.totalTokens;
      if (tokens !== undefined) {
        tokenCount = tokens;
      }
    }

    return {
      content: fullContent,
      ...(toolCalls ? { toolCalls } : {}),
      ...(tokenCount !== undefined ? { usage: { completionTokens: tokenCount } } : {}),
    };
  }

  /**
   * Execute one tool call, mirroring {@link TurnExecutor.runTool}: unknown
   * tools and thrown errors are captured as `error` (not raised) and fed
   * back to the model.
   */
  private async runTool(
    call: ModelToolCall,
    toolMap: Map<string, SessionTool>,
  ): Promise<{ content: string; isError: boolean }> {
    const tool = toolMap.get(call.name);
    if (!tool) {
      const message = `Unknown tool: ${call.name}`;
      this.log.warn(message);
      return { content: message, isError: true };
    }
    try {
      const result = await tool.invoke(call.args);
      return { content: stringifyResult(result), isError: false };
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(`Tool ${call.name} failed:`, message);
      return { content: message, isError: true };
    }
  }

  // ──────────────────────── frame builders ────────────────────────

  private async emitAccept(requestId: string, sessionId: string): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_ACCEPT, {
        requestId,
        sessionId,
      }),
    );
  }

  private async emitDelta(
    requestId: string,
    sessionId: string,
    sequenceNo: number,
    deltaIndex: number,
    content: string,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_DELTA, {
        requestId,
        sessionId,
        sequenceNo,
        deltaIndex,
        content,
      }),
    );
  }

  private async emitComplete(
    requestId: string,
    sessionId: string,
    sequenceNo: number,
    content: string,
    tokenCount?: number,
    modelCode?: string,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_COMPLETE, {
        requestId,
        sessionId,
        sequenceNo,
        content,
        ...(tokenCount !== undefined ? { tokenCount } : {}),
        ...(modelCode !== undefined ? { modelCode } : {}),
      }),
    );
  }

  private async emitFailed(
    requestId: string,
    sessionId: string,
    errorCode: string,
    message: string,
    retryHint?: string,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_FAILED, {
        requestId,
        sessionId,
        errorCode,
        message,
        ...(retryHint !== undefined ? { retryHint } : {}),
      }),
    );
  }

  private async emitToolCall(
    requestId: string,
    sessionId: string,
    toolCallId: string,
    name: string,
    args: Record<string, unknown>,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_TOOL_CALL, {
        requestId,
        sessionId,
        toolCallId,
        name,
        args,
      }),
    );
  }

  private async emitToolResult(
    requestId: string,
    sessionId: string,
    toolCallId: string,
    result: string,
    isError: boolean,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_TOOL_RESULT, {
        requestId,
        sessionId,
        toolCallId,
        result,
        isError,
      }),
    );
  }

  private async emitCancelled(
    requestId: string,
    sessionId: string,
    sequenceNo: number,
    partialContent: string,
  ): Promise<void> {
    await this.send(
      makeEnvelope(MessageType.INFERENCE_CANCELLED, {
        requestId,
        sessionId,
        sequenceNo,
        ...(partialContent ? { partialContent } : {}),
      }),
    );
  }
}

/**
 * Stringify a tool result for the transcript: pass strings through, JSON the
 * rest (mirrors the Python `json.dumps` fallback and {@link TurnExecutor}'s
 * `stringifyResult`).
 */
function stringifyResult(result: unknown): string {
  if (typeof result === "string") {
    return result;
  }
  if (result === undefined) {
    return "";
  }
  try {
    return JSON.stringify(result);
  } catch {
    return String(result);
  }
}