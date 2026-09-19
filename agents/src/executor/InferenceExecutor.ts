// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * InferenceExecutor: handles `execution.start` frames for the unified
 * host-control protocol (Plan 6 Task 3).
 *
 * The engine sends an `execution.start` frame when it wants the agent to run
 * one model turn inside an already-open {@link Session} (opened via
 * `session.open` and tracked by {@link SessionRegistry}). The executor:
 *
 *   1. Looks up the session; on miss it emits `execution.failed`
 *      (`SESSION_NOT_OPEN`).
 *   2. Emits `execution.accept`.
 *   3. Converts the wire {@link InferenceMessage}s to internal
 *      {@link ConversationMessage}s.
 *   4. Selects the active tools from `session.tools` by `activeToolNames`.
 *   5. Streams (`execution.delta` per chunk) or invokes the model once.
 *   6. On tool calls: runs the tool, appends the result, and loops back to the
 *      model (up to `maxIterations`).  (Legacy `inference.tool_call/result`
 *      emissions are deleted; tool calls are now internal to the agent.)
 *   7. On cancellation: emits `execution.cancelled` with partial content.
 *   8. Emits `execution.complete` (content + usage) or `execution.failed`.
 *
 * The tool loop reuses the pattern from {@link TurnExecutor.runTool}:
 * unknown tools and thrown errors are recorded as tool errors and fed back
 * to the model rather than aborting the turn.
 */
import type { Logger } from "../models/index.js";
import type {
  ExecutionAcceptPayload,
  ExecutionCancelPayload,
  ExecutionCancelledPayload,
  ExecutionCompletePayload,
  ExecutionDeltaPayload,
  ExecutionFailedPayload,
  ExecutionStartPayload,
  InferenceMessage,
} from "../protocol/unifiedFrames.js";
import type { SessionRegistry, Session } from "../session/SessionRegistry.js";
import type {
  CancellationSignal,
  ChatModel,
  ConversationMessage,
  ExecutorEvents,
  ModelResponse,
  ModelStreamChunk,
  ModelToolCall,
  ToolSpec,
  SessionTool,
  RiskClass,
} from "./types.js";
import type { EngineHttpClient } from "../transport/httpClient.js";
import type { ApprovalCoordinator } from "./ApprovalCoordinator.js";
import type { ExecutionFrameSender } from "./ExecutionFrameSender.js";
import type { PolicyEnforcer } from "./PolicyEnforcer.js";

/** Constructor options for {@link InferenceExecutor}. */
export interface InferenceExecutorOptions {
  /** Session lookup — must be the same registry the supervisor feeds
   * `session.open`/`session.close` into. */
  registry: SessionRegistry;
  /** Outbound unified execution-frame sender (typically HostControlClient). */
  sender: ExecutionFrameSender;
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
  /** §8.4 conversation event stream (TurnExecutor callbacks → execution.event
   * frames, capture-filtered). Optional; when absent the turn runs silently
   * exactly as before this seam existed. */
  events?: ExecutorEvents;
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
  private readonly sender: ExecutionFrameSender;
  private readonly httpClient?: EngineHttpClient;
  private readonly agentAccessToken?: string;
  private readonly maxIterations: number;
  private readonly approvals?: ApprovalCoordinator;
  private readonly events?: ExecutorEvents;
  private readonly log: Logger;

  /** In-flight executions keyed by `executionId`, each with its abort controller
   * so an `execution.cancel` can stop the matching turn. */
  private readonly inFlight = new Map<
    string,
    { controller: AbortController; partialContent: string }
  >();

  constructor(options: InferenceExecutorOptions) {
    this.registry = options.registry;
    this.sender = options.sender;
    this.httpClient = options.httpClient;
    this.agentAccessToken = options.agentAccessToken;
    this.maxIterations = options.maxIterations ?? DEFAULT_MAX_ITERATIONS;
    this.approvals = options.approvals;
    this.events = options.events;
    this.log = options.logger ?? noopLogger;
  }

  /**
   * Retrieve knowledge-source hits for RAG grounding (§7.3).
   * Available to both service types via the executor's turn context.
   */
  async retrieve(
    sourceId: string,
    query: string,
    options?: { topK?: number; filters?: Record<string, string>; taskId?: string; attemptId?: string },
  ): Promise<unknown[]> {
    if (!this.httpClient || !this.agentAccessToken) {
      return [];
    }
    try {
      return await this.httpClient.retrieve(this.agentAccessToken, {
        sourceId,
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
   * Main handler for an `execution.start` frame.
   *
   * Flow: session lookup → `execution.accept` → convert messages → select
   * tools → stream/invoke → tool loop → `execution.complete`/`execution.failed`.
   * On cancellation, emits `execution.cancelled` with whatever partial content
   * was accumulated.
   */
  async handleStart(payload: ExecutionStartPayload): Promise<void> {
    const { executionId, sessionId } = payload;

    // 1. Session lookup — with retry because session.open() is async
    //    (it resolves the LLM model) and may still be in-flight when
    //    execution.start arrives. Wait up to 10s for it to appear.
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
        executionId,
        "SESSION_NOT_OPEN",
        `No active session for sessionId: ${sessionId}`,
      );
      return;
    }

    // Register the in-flight turn for cancellation.
    const controller = new AbortController();
    const state = { controller, partialContent: "" };
    this.inFlight.set(executionId, state);
    // §8.7 (A4): register the execution's enforcement state (the session's
    // enforcer is shared; the executionId alias lets policy updates find it).
    this.registry.registerExecution(sessionId, executionId);

    try {
      // 2. Emit execution.accept.
      await this.emitAccept(executionId, sessionId, payload);

      // §8.4: bind the event reporter to THIS execution for the turn, so
      // every emitted frame carries the executionId (cleared in `finally`).
      this.events?.beginExecution?.(executionId);

      // 3. Convert wire messages to internal transcript.
      const messages = this.toConversationMessages(payload.input?.messages ?? []);

      // 4. Select active tools from the session's tool map.
      const activeToolNames = payload.toolPolicy?.activeToolNames ?? [];
      const tools = this.selectTools(session, activeToolNames);
      const toolSpecs: ToolSpec[] = tools.map(({ name, description, parameters }) => ({
        name,
        description,
        parameters,
      }));

      // §8.7 (A4): the execution's enforcement state — monotonic local
      // accounting seeded from the session's §7.3 policy block.
      const enforcer = this.registry.enforcerFor(sessionId);

      this.log.info(
        `Execution ${executionId}: ${toolSpecs.length} active tools (${toolSpecs.map(t => t.name).join(', ') || 'none'}), streaming=${payload.output?.stream ?? false}`
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
        enforcer,
      );

      // 7. Cancellation takes precedence over completion.
      if (cancellation.cancelled) {
        await this.emitCancelled(
          executionId,
          payload.sequenceNo ?? 0,
          state.partialContent,
        );
        return;
      }

      // 8. Emit execution.complete (or execution.failed on error).
      if (result.kind === "complete") {
        await this.emitComplete(
          executionId,
          payload.sequenceNo ?? 0,
          result.content,
          result.tokenCount,
          result.modelCode,
        );
      } else {
        await this.emitFailed(
          executionId,
          result.errorCode,
          result.message,
        );
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(`Execution turn failed for ${executionId}:`, message);
      await this.emitFailed(
        executionId,
        "PROVIDER_ERROR",
        message,
      );
    } finally {
      this.inFlight.delete(executionId);
      // §8.4: drop the per-turn reporter binding so a stale execution id
      // can never ride a later callback.
      this.events?.endExecution?.();
    }
  }

  /**
   * Handle an `execution.cancel` frame: abort the matching in-flight turn.
   * The main loop in {@link handleStart} observes the abort and emits
   * `execution.cancelled` with the partial content accumulated so far.
   */
  handleCancel(payload: ExecutionCancelPayload): void {
    const state = this.inFlight.get(payload.executionId);
    if (!state) {
      this.log.debug(
        `No in-flight execution to cancel for executionId: ${payload.executionId}`,
      );
      return;
    }
    this.log.info(`Cancelling in-flight execution ${payload.executionId}`);
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
        role: m.role as ConversationMessage["role"],
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
    payload: ExecutionStartPayload,
    state: { controller: AbortController; partialContent: string },
    enforcer?: PolicyEnforcer,
  ): Promise<
    | { kind: "complete"; content: string; tokenCount?: number; modelCode?: string }
    | { kind: "failed"; errorCode: string; message: string }
  > {
    const { executionId } = payload;
    const toolMap = new Map<string, SessionTool>(tools.map((t) => [t.name, t]));
    const model = session.model;

    let iteration = 0;
    while (iteration < this.maxIterations) {
      if (cancellation.cancelled) {
        this.log.info("Execution cancelled; stopping tool loop");
        return {
          kind: "complete",
          content: state.partialContent,
        };
      }
      // §8.7 (A4): the enforced ceiling is checked BEFORE every model call —
      // when local accounting reached the allowance (or the session's
      // iteration policy), the execution pauses: no further model calls pass
      // the ceiling (pause is the terminal answer until resumed).
      // §7.3 (§12.2): the wall-clock deadline joins the same boundary —
      // a turn whose policy executionTimeoutSeconds elapsed pauses instead
      // of starting another model call. Both surface FAILED with
      // POLICY_CEILING_REACHED: the unified conversation turn has no
      // suspension store to resume from (§8.7 pause = the terminal answer
      // for this executor's turn shape, matching the ITERATION_LIMIT
      // convention above).
      if (enforcer) {
        const decision = enforcer.check();
        if (decision.kind === "paused") {
          enforcer.notePause(decision);
          return {
            kind: "failed",
            errorCode: "POLICY_CEILING_REACHED",
            message: decision.message,
          };
        }
        const deadlineDecision = enforcer.checkDeadline();
        if (deadlineDecision.kind === "paused") {
          enforcer.notePause(deadlineDecision);
          return {
            kind: "failed",
            errorCode: "POLICY_CEILING_REACHED",
            message: deadlineDecision.message,
          };
        }
      }
      iteration += 1;
      this.log.debug(`Execution iteration ${iteration} for ${executionId}`);

      let response: ModelResponse;
      try {
        if (payload.output?.stream && typeof model.stream === "function") {
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

      // §8.7 (A4): record the response's accounted tokens into the
      // monotonic local accounting (provider-reported deltas only).
      if (enforcer) {
        const delta = this.responseTokenDelta(response);
        if (delta > 0) {
          enforcer.recordTokens(delta);
        }
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

      // Execute each requested tool call and feed the result back into the
      // transcript. Legacy inference.tool_call/result emissions are deleted.
      for (const call of requested) {
        if (cancellation.cancelled) {
          break;
        }

        // HITL risk gate (UC-014 §A4a): before executing a DESTRUCTIVE or
        // IRREVERSIBLE tool on a project with autoHitlOnDestructive=true,
        // emit execution.approval.requested and block until the human decides.
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

          let outcome;
          try {
            outcome = await this.approvals!.requestApproval({
              executionId,
              content: `Approve ${call.name}?`,
              payload: { toolName: call.name, args: call.args },
            });
          } catch (err) {
            // ApprovalTimeoutError or send failure — treat as rejection.
            this.log.warn(
              `Approval request for ${call.name} failed: ${err instanceof Error ? err.message : String(err)}`,
            );
            const rejectContent = `Tool '${call.name}' was not approved: approval request failed.`;
            messages.push({ role: "tool", content: rejectContent, toolCallId: call.id });
            continue;
          }

          if (!outcome.approved) {
            const rejectContent = outcome.comment
              ? `Tool '${call.name}' was rejected by a human: ${outcome.comment}`
              : `Tool '${call.name}' was rejected by a human.`;
            this.log.info(`Approval for ${call.name} rejected — skipping tool execution`);
            messages.push({ role: "tool", content: rejectContent, toolCallId: call.id });
            continue;
          }

          this.log.info(`Approval for ${call.name} approved — proceeding with tool execution`);
        }

        // §8.4 TOOL_STARTED — metadata only (toolName + callId); the
        // record's args never pass the session's capture filter.
        await this.events?.onToolStart?.({
          toolCallId: call.id,
          toolName: call.name,
          args: call.args,
          startedAt: Date.now(),
        });

        const result = await this.runTool(call, toolMap);

        // §8.4 TOOL_COMPLETED — metadata only (toolName, callId, durationMs,
        // isError); the result/error content never passes the filter.
        await this.events?.onToolEnd?.({
          toolCallId: call.id,
          toolName: call.name,
          args: call.args,
          startedAt: Date.now(),
          completedAt: Date.now(),
          ...(result.isError ? { error: result.content } : { result: result.content }),
        });

        messages.push({
          role: "tool",
          content: result.content,
          toolCallId: call.id,
        });
      }
    }

    // Iteration cap hit without a final answer.
    this.log.warn(`Max iterations (${this.maxIterations}) reached for ${executionId}`);
    return {
      kind: "failed",
      errorCode: "MAX_ITERATIONS",
      message: `Execution did not converge within ${this.maxIterations} iterations`,
    };
  }

  /**
   * §8.7 (A4): the provider-reported token delta of ONE model response
   * (mirrors TurnExecutor's per-response accounting — integer counts only,
   * never estimated).
   */
  private responseTokenDelta(response: ModelResponse): number {
    const u = response.usage;
    if (!u) {
      return 0;
    }
    const prompt = Number.isInteger(u.promptTokens) && u.promptTokens! >= 0 ? u.promptTokens! : 0;
    const completion =
      Number.isInteger(u.completionTokens) && u.completionTokens! >= 0 ? u.completionTokens! : 0;
    return prompt + completion;
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
    payload: ExecutionStartPayload,
    state: { controller: AbortController; partialContent: string },
    cancellation: CancellationSignal,
  ): Promise<ModelResponse> {
    const { executionId } = payload;
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
          executionId,
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

  // ──────────────────────── unified frame builders ────────────────────────

  private async emitAccept(
    executionId: string,
    _sessionId: string,
    payload: ExecutionStartPayload,
  ): Promise<void> {
    const accept: ExecutionAcceptPayload = {
      executionId,
      startedAt: new Date().toISOString(),
      resolvedModelId: "unknown",
      dispatchId: payload.requestId ?? executionId,
      assignmentDigest: "",
    };
    await this.sender.sendExecutionAccept(accept);
  }

  private async emitDelta(
    executionId: string,
    deltaIndex: number,
    content: string,
  ): Promise<void> {
    const delta: ExecutionDeltaPayload = {
      executionId,
      index: deltaIndex,
      content,
      contentType: "TEXT",
    };
    await this.sender.sendExecutionDelta(delta);
  }

  private async emitComplete(
    executionId: string,
    _sequenceNo: number,
    content: string,
    tokenCount?: number,
    modelCode?: string,
  ): Promise<void> {
    const complete: ExecutionCompletePayload = {
      executionId,
      completedAt: new Date().toISOString(),
      result: { content, structured: null, artifacts: null },
      usage: {
        modelId: modelCode ?? null,
        inputTokens: null,
        outputTokens: tokenCount ?? null,
        durationMs: null,
      },
    };
    await this.sender.sendExecutionComplete(complete);
  }

  private async emitFailed(
    executionId: string,
    errorCode: string,
    message: string,
  ): Promise<void> {
    const failed: ExecutionFailedPayload = {
      executionId,
      failedAt: new Date().toISOString(),
      error: {
        code: errorCode,
        message,
        category: null,
        retryable: errorCode === "PROVIDER_ERROR" || errorCode === "TRANSIENT",
        retryAfterSeconds: null,
      },
      usage: {
        modelId: null,
        inputTokens: null,
        outputTokens: null,
        durationMs: null,
      },
    };
    await this.sender.sendExecutionFailed(failed);
  }

  private async emitCancelled(
    executionId: string,
    _sequenceNo: number,
    _partialContent: string,
  ): Promise<void> {
    const cancelled: ExecutionCancelledPayload = {
      executionId,
      dispatchId: executionId,
      cancelledAt: new Date().toISOString(),
      reasonCode: "USER_REQUEST",
    };
    await this.sender.sendExecutionCancelled(cancelled);
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