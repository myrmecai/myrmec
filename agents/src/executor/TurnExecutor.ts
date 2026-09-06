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
  Tool,
  ToolSpec,
} from "./types.js";
import type { BudgetController } from "../orchestration/BudgetController.js";

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
      try {
        response = await run.model.invoke(messages, toolSpecs);
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
        const record = await this.runTool(call, toolMap, run.events);
        toolCalls.push(record);
        messages.push({
          role: "tool",
          content: record.error ?? stringifyResult(record.result),
          toolCallId: call.id,
        });
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
