// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * TaskDispatcher: the seam between the supervisor's inbound frames and the
 * stateless {@link TurnExecutor}.
 *
 * Ported from the Python agent's task lifecycle (`_handle_task_assign` →
 * `_accept_task` → `_execute_task`, plus `_handle_task_cancel`):
 * - one task at a time; a second assign while busy is rejected;
 * - the busy claim is made *synchronously* before the first await so two
 *   assigns racing through the receive loop cannot both accept;
 * - the task runs in the background (not awaited in the handler) so the
 *   receive loop stays responsive to `task.cancel`;
 * - tool frames and progress are emitted *live* via executor events;
 * - a cancelled run emits no terminal frame (the engine drove the cancel).
 */
import type { Logger, ToolCallRecord } from "../models/index.js";
import type { Envelope } from "../protocol/envelope.js";
import {
  taskAccept,
  taskComplete,
  taskFailed,
  taskProgress,
  taskReject,
  toolCall,
  toolResult,
  taskAssignPayloadSchema,
  taskCancelPayloadSchema,
} from "../protocol/taskFrames.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import { assembleTask } from "./assembleTask.js";
import { TurnExecutor, stringifyResult } from "./TurnExecutor.js";
import type { ChatModel, Tool } from "./types.js";

/** Resolves the model to run a task against, from the engine's descriptor. */
export type ModelResolver = (
  info: ModelInfoWire | undefined,
) => ChatModel | Promise<ChatModel>;

/** Collaborators the dispatcher needs, injected by the supervisor. */
export interface TaskDispatcherOptions {
  /** Send a frame on the supervisor's connection. */
  send: (frame: Envelope) => Promise<void>;
  /** A fixed model adapter used for every task. Takes precedence over
   * {@link resolveModel} when supplied. */
  model?: ChatModel;
  /** Resolves a model per task from the engine-supplied descriptor. Used when
   * no fixed {@link model} is given. */
  resolveModel?: ModelResolver;
  /** Tools the agent can offer; filtered per task to the engine-authorized set. */
  tools?: Tool[];
  /** Iteration cap passed to the executor. */
  maxIterations?: number;
  logger?: Logger;
}

const noopLogger: Logger = {
  debug: () => {},
  info: () => {},
  warn: () => {},
  error: () => {},
};

/** Mutable cancellation flag shared with the running executor. */
interface MutableCancellation {
  cancelled: boolean;
}

export class TaskDispatcher {
  private readonly send: (frame: Envelope) => Promise<void>;
  private readonly model?: ChatModel;
  private readonly modelResolver?: ModelResolver;
  private readonly tools: Tool[];
  private readonly executor: TurnExecutor;
  private readonly log: Logger;

  private currentTaskId: string | null = null;
  private cancellation: MutableCancellation | null = null;

  constructor(options: TaskDispatcherOptions) {
    if (!options.model && !options.resolveModel) {
      throw new Error(
        "TaskDispatcher requires either a fixed `model` or a `resolveModel` factory",
      );
    }
    this.send = options.send;
    this.model = options.model;
    this.modelResolver = options.resolveModel;
    this.tools = options.tools ?? [];
    this.log = options.logger ?? noopLogger;
    this.executor = new TurnExecutor({
      maxIterations: options.maxIterations,
      logger: this.log,
    });
  }

  /** True while a task is being executed. */
  get isBusy(): boolean {
    return this.currentTaskId !== null;
  }

  /** Handle an inbound `task.assign` payload. */
  handleAssign(payload: unknown): void {
    const parsed = taskAssignPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.error("Malformed task.assign payload; dropping", parsed.error);
      return;
    }
    const wire = parsed.data;

    // Synchronous busy check + claim, before any await, so a second assign
    // cannot slip past while this one is still resolving.
    if (this.isBusy) {
      this.log.warn(
        `Rejecting task ${wire.taskId}; agent is busy with ${this.currentTaskId}`,
      );
      void this.send(taskReject(wire.taskId, "Agent is busy")).catch((err) =>
        this.log.error("Failed to send task.reject", err),
      );
      return;
    }
    this.currentTaskId = wire.taskId;
    this.cancellation = { cancelled: false };

    // Fire and forget: keep the receive loop free for task.cancel.
    void this.run(wire).finally(() => {
      this.currentTaskId = null;
      this.cancellation = null;
    });
  }

  /** Handle an inbound `task.cancel` payload. */
  handleCancel(payload: unknown): void {
    const parsed = taskCancelPayloadSchema.safeParse(payload);
    if (!parsed.success) {
      this.log.error("Malformed task.cancel payload; dropping", parsed.error);
      return;
    }
    const { taskId, reason } = parsed.data;
    if (this.currentTaskId === taskId && this.cancellation) {
      this.log.info(`Cancelling task ${taskId}: ${reason}`);
      this.cancellation.cancelled = true;
    } else {
      this.log.debug(`Ignoring cancel for non-current task ${taskId}`);
    }
  }

  /** Accept, execute, and report a single task. */
  private async run(
    wire: ReturnType<typeof taskAssignPayloadSchema.parse>,
  ): Promise<void> {
    const taskId = wire.taskId;
    const cancellation = this.cancellation ?? { cancelled: false };

    try {
      await this.send(taskAccept(taskId));
    } catch (err) {
      this.log.error("Failed to send task.accept", err);
      return;
    }

    const task = assembleTask(wire);

    // Resolve the model the engine routed this task to (or the fixed model).
    let model: ChatModel;
    try {
      model = await this.resolveModel(wire.model ?? undefined);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(`Could not resolve a model for task ${taskId}`, message);
      await this.send(
        taskFailed(taskId, { error: message, errorCode: "MODEL_UNAVAILABLE" }),
      ).catch((sendErr) =>
        this.log.error("Failed to send task.failed", sendErr),
      );
      return;
    }

    // Authorize tools down to the engine-declared set for this task.
    const allowed = new Set(task.context.toolNames);
    const tools = this.tools.filter((t) => allowed.has(t.name));

    const emitToolStart = (record: ToolCallRecord) =>
      this.send(
        toolCall(taskId, {
          toolName: record.toolName,
          callId: record.toolCallId,
          input: record.args,
        }),
      ).catch((err) => this.log.error("Failed to send tool.call", err));

    const emitToolEnd = (record: ToolCallRecord) => {
      const durationMs =
        (record.completedAt ?? record.startedAt) - record.startedAt;
      return this.send(
        toolResult(taskId, {
          callId: record.toolCallId,
          durationMs,
          ...(record.error !== undefined
            ? { error: record.error }
            : { output: { result: stringifyResult(record.result) } }),
        }),
      ).catch((err) => this.log.error("Failed to send tool.result", err));
    };

    const emitProgress = (progress: number) =>
      this.send(taskProgress(taskId, progress)).catch((err) =>
        this.log.error("Failed to send task.progress", err),
      );

    let result;
    try {
      result = await this.executor.execute(task, {
        model,
        tools,
        cancellation,
        events: {
          onProgress: emitProgress,
          onToolStart: emitToolStart,
          onToolEnd: emitToolEnd,
        },
      });
    } catch (err) {
      // Defensive: the executor classifies its own failures, but a thrown
      // error here is still terminal.
      const message = err instanceof Error ? err.message : String(err);
      this.log.error(`Executor threw for task ${taskId}`, message);
      await this.send(
        taskFailed(taskId, { error: message, errorCode: "EXECUTOR_ERROR" }),
      ).catch((sendErr) =>
        this.log.error("Failed to send task.failed", sendErr),
      );
      return;
    }

    if (result.status === "CANCELLED") {
      // The engine drove the cancel; it expects no terminal frame.
      this.log.info(`Task ${taskId} cancelled; no terminal frame sent`);
      return;
    }

    if (result.status === "COMPLETE") {
      await this.send(
        taskComplete(taskId, { response: result.completion ?? "" }),
      ).catch((err) => this.log.error("Failed to send task.complete", err));
      return;
    }

    // FAILED
    const failure = result.failure;
    await this.send(
      taskFailed(taskId, {
        error: failure?.message ?? "Task failed",
        errorCode: failure?.finishReason,
      }),
    ).catch((err) => this.log.error("Failed to send task.failed", err));
  }

  /** Pick the model for a task: the fixed model wins, else the resolver. */
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
