// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * The turn loop: the provider-agnostic core of task/turn execution.
 *
 * Ported from the agent loop in the Python `LangChainExecutor.execute`
 * (bind tools → invoke → handle tool_calls → repeat until a final answer or
 * the iteration cap). It runs against the {@link ChatModel} / {@link Tool}
 * seams so it can be exercised with fakes and so real provider adapters layer
 * on later (§9.3). It owns no transport: it consumes a {@link Task} and
 * returns a {@link TaskResult}, recording every tool call as a
 * {@link ToolCallRecord} for audit/replay (REQ-A-070/072).
 */
import type { Logger, Task, TaskResult, ToolCallRecord } from "../models/index.js";
import type {
  CancellationSignal,
  ChatModel,
  ConversationMessage,
  ExecutorEvents,
  ModelCallOptions,
  ModelResponse,
  ModelToolCall,
  TokenUsage,
  Tool,
  ToolSpec,
} from "./types.js";
import type { BudgetController } from "../orchestration/BudgetController.js";
import type {
  ExecutionControl,
  SafePoint,
} from "../interaction/ExecutionControlCoordinator.js";
import { HoldAbortedError } from "../interaction/ExecutionControlCoordinator.js";
import type { AttemptModelScheduler } from "../interaction/AttemptModelScheduler.js";

/** The turn's model-permit scheduler rechecked budget verdict (Task 5:
 * the scheduler is not a leaf - budget/policy are rechecked AFTER the
 * permit, so a queued call cannot spend an already-breached budget).
 * Thrown as control flow, mirroring the HITL/policy ceiling signals. */
class BudgetRecheckSignal extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "BudgetRecheckSignal";
  }
}

/** The safe point of each gated boundary inside the turn loop (14.2).
 * The model boundary (BEFORE_MODEL_CALL) is exercised by the
 * AttemptModelScheduler's internal park point, not a TurnExecutor leaf
 * lease — the permit is not a leaf (14.5). */
const TOOL_SAFE_POINT: SafePoint = "BEFORE_TOOL_EXECUTION";

/** Policy options for the executor (constructor-level). */
export interface TurnExecutorOptions {
  /** Hard cap on model↔tool iterations to prevent infinite loops
   * (default 25, matching the Python SDK). */
  maxIterations?: number;
  logger?: Logger;
}

/** Per-run collaborators resolved by the caller (supervisor / provider slice). */
export interface TurnRun {
  /** Model adapter resolved from `task.model`. */
  model: ChatModel;
  /** Tools available this turn, already filtered to `task.context.toolNames`. */
  tools?: Tool[];
  /** Cooperative cancellation signal. */
  cancellation?: CancellationSignal;
  /** Optional live observability callbacks (progress, tool frames). */
  events?: ExecutorEvents;
  /** Per-run iteration cap override (design §7.2: orchestration turns
   * carry explicit limits that never inherit the constructor default).
   * Takes precedence over the constructor's maxIterations. */
  maxIterationsOverride?: number;
  /** Design §13: the shared per-attempt BudgetController. When present
   * every model response's tokens are recorded and the token budget is
   * consulted inside the model-tool loop — a response that crosses the
   * limit has its tool calls skipped, and the executor returns the
   * breach as a budget failure (kind PERMANENT, finishReason
   * TOKEN_BUDGET_EXCEEDED). */
  budget?: BudgetController;
  /** §14.2 (Task 5): the serialized hold gate. When present, tool
   * execution is admitted through `enterOperation("BEFORE_TOOL_…")` -
   * a HELD gate parks the tool until CONTINUE; stop/deadline aborts it
   * with HoldAbortedError (the loop maps that to CANCELLED). The model
   * call is gated with the injected `modelScheduler` (the attempt-level
   * permit + interaction priority seam). */
  control?: ExecutionControl;
  /** §14.5 (Task 5): the attempt-level model scheduler - ONE permit per
   * invocation, INTERACTION priority, gate+budget recheck after
   * acquisition. Present only on orchestration turns. */
  modelScheduler?: AttemptModelScheduler;
  /** §22.5 (Task 5): tool names whose execution must NOT take a
   * BEFORE_TOOL_EXECUTION leaf because their bodies span a nested
   * turn whose own boundaries gate themselves (the runner-owned
   * invoke_helper delegation: its admission is already gated at the
   * runner's BEFORE_HELPER_CALL boundary, and the child turn carries
   * the same control/scheduler — a leaf spanning the child would make
   * HELD unreachable while a helper runs, violating 14.3/22.5). */
  ungatedToolNames?: readonly string[];
  /** §22.6 (Task 7): provisional output callback. When present, the
   * sanitized fragment stream (stream() chunks; on the invoke fallback a
   * single final fragment) is handed here BEFORE the final response is
   * assembled - the executor never fabricates frames; the callback owns
   * the wire emission. Never called with tool-call fragments or usage. */
  onOutput?: (text: string) => Promise<void>;
  /** §14.6 (Task 7): called before EACH model response's fragments (a
   * fresh model invocation inside the tool loop) so the fragment
   * consumer can segment per-response - the authoritative answer is
   * the FINAL response's sanitized content. */
  onModelResponseStart?: () => void;
  /** §22.6 (Task 7): the abort signal threaded to the model's third
   * {signal} argument. Provider support is adapter-dependent; the caller
   * (the interaction deadline / cancellation fencing) owns the timing. */
  modelSignal?: AbortSignal;
  /** §14.5 (Task 7): which internal loop this turn's model invocations
   * belong to on the shared attempt scheduler. Defaults to
   * ORCHESTRATION; the InteractiveController's turns pass INTERACTION to
   * take priority at the next permit release. */
  modelSchedulerSource?: "ORCHESTRATION" | "INTERACTION";
}

const noopLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

export class TurnExecutor {
  private readonly maxIterations: number;
  private readonly log: Logger;

  constructor(options: TurnExecutorOptions = {}) {
    this.maxIterations = options.maxIterations ?? 25;
    this.log = options.logger ?? noopLogger;
  }

  /**
   * Run a task/turn to completion.
   *
   * Outcomes:
   * - COMPLETE  — the model returned a final answer (`completion`).
   * - CANCELLED — the cancellation signal was observed.
   * - FAILED    — the model call threw (TRANSIENT/PROVIDER_ERROR) or the
   *               iteration cap was hit (PERMANENT/MAX_ITERATIONS).
   *
   * Tool failures do **not** fail the task: the error is recorded on the
   * {@link ToolCallRecord} and fed back to the model, mirroring the Python loop.
   */
  async execute(task: Task, run: TurnRun): Promise<TaskResult> {
    try {
      return await this.runLoop(task, run);
    } catch (err) {
      if (err instanceof HoldAbortedError) {
        // §22.5: gate/scheduler aborts (stop/deadline/cancel/channel
        // loss) are TERMINATION control flow — the run reports
        // CANCELLED with the evidence collected so far; the runner/
        // executor's own cancellation path decides the terminal frame.
        this.log.info(`Hold gate aborted the turn: ${err.message}`);
        return this.cancelled(task, []);
      }
      if (err instanceof BudgetRecheckSignal) {
        // §14.5: the post-permit budget recheck failed — the same
        // PERMANENT budget-failure shape as the loop's inline checks.
        return {
          taskId: task.taskId,
          status: "FAILED",
          failure: {
            kind: "PERMANENT",
            finishReason: err.code,
            message: err.message,
          },
          toolCalls: [],
        };
      }
      throw err;
    }
  }

  /** The model↔tool loop; `execute` owns the §22.5 abort mapping. */
  private async runLoop(task: Task, run: TurnRun): Promise<TaskResult> {
    const toolCalls: ToolCallRecord[] = [];
    const toolMap = new Map<string, Tool>(
      (run.tools ?? []).map((t) => [t.name, t]),
    );
    const toolSpecs: ToolSpec[] = (run.tools ?? []).map(
      ({ name, description, parameters }) => ({ name, description, parameters }),
    );
    // Per-run override beats the constructor default (design §7.2).
    const iterationCap = run.maxIterationsOverride ?? this.maxIterations;
    const messages = this.buildMessages(task);

    // Aggregated provider-reported usage across model calls (REQ-A-071).
    // Providers that report nothing leave this undefined — orchestration
    // treats that as TOKEN_USAGE_UNAVAILABLE at its boundary.
    let usage: { promptTokens: number; completionTokens: number; totalTokens: number } | undefined;

    let iteration = 0;
    while (iteration < iterationCap) {
      if (run.cancellation?.cancelled) {
        this.log.info("Task cancelled; stopping execution");
        return { ...this.cancelled(task, toolCalls), usage };
      }
      // Design §13: check before the next model call too — once the
      // recorded usage crossed the limit, no further model invocation.
      if (run.budget) {
        const breach = run.budget.check("before-model-call");
        if (breach) {
          this.log.warn(`Budget exceeded at ${breach.checkpoint}: ${breach.message}`);
          return {
            taskId: task.taskId,
            status: "FAILED",
            failure: {
              kind: "PERMANENT",
              finishReason: breach.errorCode,
              message: breach.message,
            },
            toolCalls,
            usage,
          };
        }
      }
      iteration += 1;
      this.log.debug(`Invoking model (iteration ${iteration})`);
      // Coarse progress, mirroring the Python loop (capped at 90 until done).
      const progress = Math.min(90, iteration * 10);
      await run.events?.onProgress?.(progress, iteration);

      let response;
      if (run.modelScheduler) {
        // §14.5: serialize the invocation through the attempt-level
        // permit (interaction priority). The scheduler RELEASES the
        // permit before parking at the hold gate, so a queued model call
        // never pins the permit while HELD; after (re)acquisition the
        // budget/cancellation are rechecked INSIDE the permit so a
        // queued call can never spend an already-breached budget or run
        // after cancellation (14.5 "all calls recheck budget/policy
        // after acquiring the permit"; §22.6: scheduler wait counts
        // toward the interaction deadline).
        try {
          response = await run.modelScheduler.invoke(
            run.modelSchedulerSource ?? "ORCHESTRATION",
            async () => {
              const breach = run.budget?.check("before-model-call") ?? null;
              if (breach) {
                throw new BudgetRecheckSignal(breach.errorCode, breach.message);
              }
              if (run.cancellation?.cancelled) {
                this.log.info("Task cancelled while queued for the model permit");
                throw new BudgetRecheckSignal(
                  "CANCELLED_BEFORE_MODEL",
                  "cancelled while waiting for the model permit",
                );
              }
              // The SCHEDULED boundary itself carries the gate evidence
              // for snapshots (the permit is not a leaf): the gate
              // parks the acquisition INSIDE the scheduler; a leaf
              // lease is entered only for TOOL execution below.
              return await this.modelCallResponse(run, messages, toolSpecs);
            },
          );
        } catch (err) {
          if (err instanceof BudgetRecheckSignal && err.code === "CANCELLED_BEFORE_MODEL") {
            return { ...this.cancelled(task, toolCalls), usage };
          }
          // Provider errors arrive wrapped by nothing: classify inline.
          if (
            !(err instanceof BudgetRecheckSignal) &&
            !(err instanceof HoldAbortedError)
          ) {
            const message = err instanceof Error ? err.message : String(err);
            this.log.error("Model invocation failed:", message);
            return {
              taskId: task.taskId,
              status: "FAILED",
              failure: {
                kind: "TRANSIENT",
                finishReason: "PROVIDER_ERROR",
                message,
              },
              toolCalls,
              usage,
            };
          }
          throw err;
        }
      } else {
        try {
          response = await this.modelCallResponse(run, messages, toolSpecs);
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          this.log.error("Model invocation failed:", message);
          return {
            taskId: task.taskId,
            status: "FAILED",
            failure: {
              kind: "TRANSIENT",
              finishReason: "PROVIDER_ERROR",
              message,
            },
            toolCalls,
            usage,
          };
        }
      }

      // Accumulate authoritative usage when the provider reported it.
      // Non-integer or negative counts are ignored (never estimated).
      const u = response.usage;
      let responseDelta: number | null = null;
      if (
        u &&
        Number.isInteger(u.promptTokens) && u.promptTokens! >= 0 &&
        Number.isInteger(u.completionTokens) && u.completionTokens! >= 0
      ) {
        const add = {
          promptTokens: (usage?.promptTokens ?? 0) + u.promptTokens!,
          completionTokens: (usage?.completionTokens ?? 0) + u.completionTokens!,
          totalTokens: 0,
        };
        add.totalTokens = add.promptTokens + add.completionTokens;
        // §13: record the RESPONSE's tokens — the delta this response
        // added to the turn's running usage, not the cumulative total.
        responseDelta = add.totalTokens - (usage?.totalTokens ?? 0);
        usage = add;
      }

      // Design §13: record the response's tokens in the shared budget
      // the moment they are known, then consult the controller INSIDE
      // the loop — an over-budget response's tool calls are skipped.
      if (run.budget && responseDelta !== null) {
        run.budget.recordTokens(responseDelta);
        const breach = run.budget.check("before-tool-execution");
        if (breach) {
          this.log.warn(`Budget exceeded at ${breach.checkpoint}: ${breach.message}`);
          return {
            taskId: task.taskId,
            status: "FAILED",
            failure: {
              kind: "PERMANENT",
              finishReason: breach.errorCode,
              message: breach.message,
            },
            toolCalls,
            usage,
          };
        }
      }

      const requested = response.toolCalls ?? [];
      if (requested.length === 0) {
        // No tool calls ⇒ final answer.
        this.log.info("Model returned final response");
        return {
          taskId: task.taskId,
          status: "COMPLETE",
          completion: response.content ?? "",
          toolCalls,
          usage,
        };
      }

      // Record the assistant turn (with its tool calls) before executing them.
      messages.push({
        role: "assistant",
        content: response.content ?? "",
        toolCalls: requested,
      });

      for (const call of requested) {
        if (run.cancellation?.cancelled) {
          // Stop dispatching; the loop top will return CANCELLED next pass.
          break;
        }
        // §14.2 (Task 5): the TOOL side effect is gated at
        // BEFORE_TOOL_EXECUTION. While HELD the admission parks (the
        // settled leaf = only the model response); the lease ALWAYS
        // releases in finally (a thrown tool must not leak the active
        // count — 14.3). A gate abort (stop/deadline/cancel) rethrows
        // HoldAbortedError: `execute` unwinds the whole turn to
        // CANCELLED (the runner maps it to its CANCELLED path).
        // §22.5 carve-out: a tool listed in ungatedToolNames spans a
        // nested turn (invoke_helper) — its admission is gated at the
        // runner's BEFORE_HELPER_CALL boundary and the child turn
        // carries its own control/scheduler, so the parent must NOT
        // hold a tool leaf across the child (else HELD is unreachable
        // while a helper runs).
        const spansNestedTurn = (run.ungatedToolNames ?? []).includes(call.name);
        if (run.control && !spansNestedTurn) {
          const lease = await run.control.enterOperation(TOOL_SAFE_POINT);
          try {
            const record = await this.runTool(call, toolMap, run.events);
            toolCalls.push(record);
            messages.push({
              role: "tool",
              content: record.error ?? stringifyResult(record.result),
              toolCallId: call.id,
            });
          } finally {
            lease.release();
          }
        } else {
          const record = await this.runTool(call, toolMap, run.events);
          toolCalls.push(record);
          messages.push({
            role: "tool",
            content: record.error ?? stringifyResult(record.result),
            toolCallId: call.id,
          });
        }
      }
    }

    // Iteration cap hit without a final answer.
    this.log.warn(`Max iterations (${iterationCap}) reached`);
    return {
      taskId: task.taskId,
      status: "FAILED",
      failure: {
        kind: "PERMANENT",
        finishReason: "MAX_ITERATIONS",
        message: `Turn did not converge within ${iterationCap} iterations`,
      },
      toolCalls,
      usage,
    };
  }

  /** Assemble the initial transcript from the engine-supplied context. */
  private buildMessages(task: Task): ConversationMessage[] {
    const messages: ConversationMessage[] = [];
    const { systemPrompt, messages: history } = task.context;
    if (systemPrompt) {
      messages.push({ role: "system", content: systemPrompt });
    }
    for (const m of history) {
      messages.push({ role: m.role, content: m.content });
    }
    return messages;
  }

  /**
   * One model call with the THIRD {signal} argument threaded (Task 7),
   * plus the optional streaming fragment path. Without `onOutput` (or a
   * stream-capable adapter) this is the plain invoke, unchanged.
   *
   * Streaming contract (14.6/22.6): content fragments go through
   * `run.onOutput` as they arrive; tool-call chunks and usage never ride
   * the callback. The stream's fragments are accumulated and the FINAL
   * response is assembled ONCE and validated exactly like invoke's.
   *
   * Degenerate-stream fallback: a stream that yields nothing (no deltas
   * at all) falls back to a plain invoke. A stream that yielded some
   * content and THEN throws is a provider error (no silent restart - the
   * user already saw provisional text).
   */
  private async modelCallResponse(
    run: TurnRun,
    messages: ConversationMessage[],
    toolSpecs: ToolSpec[],
  ): Promise<ModelResponse> {
    const options: ModelCallOptions = {
      ...(run.modelSignal !== undefined ? { signal: run.modelSignal } : {}),
    };
    if (!run.onOutput || typeof run.model.stream !== "function") {
      const response = await run.model.invoke(messages, toolSpecs, options);
      // A fresh response boundary: the fragment consumer's authoritative
      // answer is THIS response's sanitized content - stale interim
      // fragments from earlier responses never pollute it.
      run.onModelResponseStart?.();
      if (run.onOutput && (response.content ?? "").length > 0) {
        // The un-streamed provisional fragment: one final fragment.
        await run.onOutput(response.content ?? "");
      }
      return response;
    }
    return await this.streamOnce(run, messages, toolSpecs, options);
  }

  /**
   * Consume ONE stream: emit content fragments through `run.onOutput`,
   * assemble tool calls ONCE (fragmented chunks merged by id - never
   * executed as fragments), accumulate usage, and return the assembled
   * {@link ModelResponse}. See {@link modelCallResponse} for the
   * degenerate-stream fallback rules.
   */
  private async streamOnce(
    run: TurnRun,
    messages: ConversationMessage[],
    toolSpecs: ToolSpec[],
    options: ModelCallOptions,
  ): Promise<ModelResponse> {
    let content = "";
    let sawContent = false;
    let sawAny = false;
    const merged = new Map<string, ModelToolCall>();
    let usage: TokenUsage | undefined;
    try {
      for await (const chunk of run.model.stream!(messages, toolSpecs, options)) {
        sawAny = true;
        if (chunk.content) {
          if (!sawContent) {
            // The FIRST fragment of THIS response: the segment boundary
            // for the fragment consumer (the authoritative answer is the
            // FINAL response's content).
            run.onModelResponseStart?.();
          }
          content += chunk.content;
          sawContent = true;
          await run.onOutput!(chunk.content);
        }
        if (chunk.toolCalls) {
          for (const call of chunk.toolCalls) {
            const existing = merged.get(call.id);
            if (existing) {
              // Fragmented arrival: merge shallowly (a later chunk may
              // complete the arguments); the id is the assembly key.
              merged.set(call.id, {
                ...existing,
                ...call,
                args: { ...existing.args, ...call.args },
              });
            } else {
              merged.set(call.id, { ...call, args: { ...call.args } });
            }
          }
        }
        if (chunk.usage) {
          usage = chunk.usage;
        }
      }
    } catch (err) {
      if (!sawAny) {
        // The stream failed BEFORE producing anything (streaming
        // unsupported-in-fact / immediate provider failure): fall back
        // to the plain invoke (the safe fallback single final delta).
        // The fallback is a FRESH response boundary (14.6: the
        // authoritative answer is the FINAL response's content).
        run.onModelResponseStart?.();
        const response = await run.model.invoke(messages, toolSpecs, options);
        if ((response.content ?? "").length > 0) {
          await run.onOutput!(response.content ?? "");
        }
        return response;
      }
      // Partials already streamed: no silent restart - the provider
      // error propagates (the loop classifies it).
      throw err;
    }
    if (!sawAny || (!sawContent && merged.size === 0)) {
      // Empty/degenerate stream: fall back to the plain invoke path
      // (fresh response boundary, same rule as above).
      run.onModelResponseStart?.();
      const response = await run.model.invoke(messages, toolSpecs, options);
      if ((response.content ?? "").length > 0) {
        await run.onOutput!(response.content ?? "");
      }
      return response;
    }
    const toolCalls = merged.size > 0 ? [...merged.values()] : undefined;
    return {
      ...(content.length > 0 ? { content } : {}),
      ...(toolCalls ? { toolCalls } : {}),
      ...(usage ? { usage } : {}),
    };
  }

  /** Execute one tool call, capturing timing and result/error. An unknown
   * tool or a thrown error is recorded as `error` (not raised). */
  private async runTool(
    call: { id: string; name: string; args: Record<string, unknown> },
    toolMap: Map<string, Tool>,
    events?: ExecutorEvents,
  ): Promise<ToolCallRecord> {
    const record: ToolCallRecord = {
      toolCallId: call.id,
      toolName: call.name,
      args: call.args,
      startedAt: Date.now(),
    };
    // Fire the start event once, before resolving the tool — mirrors the
    // Python tracker which emits tool.call even for an unknown tool.
    await events?.onToolStart?.(record);

    const tool = toolMap.get(call.name);
    if (!tool) {
      record.error = `Unknown tool: ${call.name}`;
      record.completedAt = Date.now();
      this.log.warn(record.error);
      await events?.onToolEnd?.(record);
      return record;
    }

    try {
      record.result = await tool.invoke(call.args);
    } catch (err) {
      // §17.4 HITL: a governed-action suspension or policy denial is a
      // RUNNER-level control-flow event, not a tool error — the turn
      // must tear down immediately so the runner can return PAUSED or
      // the terminal denial. §8.7 (A4): a policy-ceiling breach is the
      // same class of control-flow signal (the attempt PAUSES at the
      // next boundary). §7.3 (§12.2): an elapsed execution-timeout
      // deadline is the same control-flow class (PAUSED with
      // EXECUTION_TIMEOUT). §14.3 (Task 5): a hold-gate abort is the
      // termination class (the turn unwinds to CANCELLED). Re-raise the
      // typed signals; every other error is recorded (the loop
      // continues).
      if (
        err instanceof Error &&
        (err.name === "ApprovalRequiredSignal" ||
          err.name === "PolicyDeniedSignal" ||
          err.name === "PolicyCeilingSignal" ||
          err.name === "ExecutionTimeoutSignal" ||
          err.name === "HoldAbortedError")
      ) {
        throw err;
      }
      record.error = err instanceof Error ? err.message : String(err);
      this.log.error(`Tool ${call.name} failed:`, record.error);
    }
    record.completedAt = Date.now();
    await events?.onToolEnd?.(record);
    return record;
  }

  private cancelled(task: Task, toolCalls: ToolCallRecord[]): TaskResult {
    return { taskId: task.taskId, status: "CANCELLED", toolCalls };
  }
}

/** Stringify a tool result for the transcript: pass strings through, JSON the
 * rest (mirrors the Python `json.dumps` fallback). */
export function stringifyResult(result: unknown): string {
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
