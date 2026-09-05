// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WorkerInvoker (design §8.5): resolves one declared worker's model
 * through the ChatModelFactory and executes it through the existing
 * TurnExecutor. Rejects undeclared workers, missing models, invalid
 * purposes, and tools outside policy — before model execution.
 */
import { randomUUID } from "node:crypto";
import type { TurnExecutor } from "../executor/TurnExecutor.js";
import type { Tool } from "../executor/types.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import type { Task, TaskResult } from "../models/index.js";
import type {
  OrchestrationAssignment,
  WorkerAuthoring,
  WorkerCallResult,
  OrchestrationErrorCode,
} from "./types.js";
/** The tools a worker invocation gets in this feature: none yet — real
 * file/command tools arrive in Feature 4 via WorkerToolFactory. */
export interface WorkerInvokerOptions {
  chatModelFactory: ChatModelFactory;
  turnExecutor: TurnExecutor;
  /** Optional per-worker tool injection (Feature 4 seam). */
  toolFactory?: (worker: WorkerAuthoring) => Promise<Tool[]>;
}

/** One worker invocation outcome with runner-owned identity. */
export interface InvokeWorkerOutcome {
  workerCall: WorkerCallResult;
  /** The worker's final answer text, bounded and redacted. */
  summary: string;
  /** Authoritative usage of the worker turn (design §13: normalized). */
  tokenCount: number;
  failure?: { code: OrchestrationErrorCode; message: string };
}

/** Normalize provider usage per design §13: a valid usage has non-negative
 * integer counts; the total is the explicit one when present, otherwise
 * computed only from both component counts; never estimated. Returns
 * null when the usage is absent or invalid. */
export function normalizeUsage(
  usage: { promptTokens?: number; completionTokens?: number; totalTokens?: number } | undefined,
): { promptTokens: number; completionTokens: number; totalTokens: number } | null {
  if (!usage) return null;
  const { promptTokens, completionTokens, totalTokens } = usage;
  const valid = (n: unknown): n is number =>
    typeof n === "number" && Number.isInteger(n) && n >= 0;
  const hasPrompt = promptTokens !== undefined;
  const hasCompletion = completionTokens !== undefined;
  // Design §13: an explicit total, when present, must itself be valid —
  // "internally inconsistent usage fails closed". A malformed explicit
  // total never falls back to computed components (never estimate).
  if (totalTokens !== undefined) {
    if (!valid(totalTokens)) return null;
    if (hasPrompt && !valid(promptTokens)) return null;
    if (hasCompletion && !valid(completionTokens)) return null;
    return {
      promptTokens: valid(promptTokens) ? promptTokens : 0,
      completionTokens: valid(completionTokens) ? completionTokens : 0,
      totalTokens,
    };
  }
  if (valid(promptTokens) && valid(completionTokens)) {
    return { promptTokens, completionTokens, totalTokens: promptTokens + completionTokens };
  }
  return null;
}

export class WorkerInvoker {
  private readonly options: WorkerInvokerOptions;
  /** Per-run tool factory injected by the runner (Feature 4): builds the
   * worker's declared tools scoped to the step workspace. */
  private toolFactory?: WorkerInvokerOptions["toolFactory"];

  constructor(options: WorkerInvokerOptions) {
    this.options = options;
  }

  /** The runner injects the workspace-scoped tool factory per run. */
  setToolFactory(toolFactory: WorkerInvokerOptions["toolFactory"]): void {
    this.toolFactory = toolFactory ?? this.options.toolFactory;
  }

  /**
   * Invoke one declared worker. Validates the declaration and the model
   * catalog entry before any model execution; expected execution failures
   * are returned, not thrown (design §7.2 boundary rule).
   */
  async invoke(
    assignment: OrchestrationAssignment,
    workerName: string,
    purpose: "IMPLEMENT" | "VERIFY",
    instruction: string,
    sequence: { startedSequence: number; completedSequence: number },
    workspace: { revisionBefore: number; revisionAfter: number },
  ): Promise<InvokeWorkerOutcome> {
    const worker = assignment.step.orchestration.workers.find((w) => w.name === workerName);
    if (!worker) {
      return this.failedOutcome(workerName, purpose, sequence, workspace, {
        code: "ASSIGNMENT_VALIDATION_ERROR",
        message: `undeclared worker: ${workerName}`,
      });
    }

    const model = assignment.models.find((m) => m.code === worker.modelCode);
    if (!model) {
      return this.failedOutcome(workerName, purpose, sequence, workspace, {
        code: "ASSIGNMENT_VALIDATION_ERROR",
        message: `worker model not in assignment: ${worker.modelCode}`,
      });
    }

    const info: ModelInfoWire = {
      provider: model.provider,
      modelId: model.modelId,
      apiEndpoint: model.apiEndpoint,
      apiKey: null, // credentialRef resolution is the adapter's job (Feature 10)
      parameters: model.parameters as Record<string, unknown>,
    };
    const resolved = await this.options.chatModelFactory.resolve(info, `worker-${randomUUID()}`);

    const tools = (await this.toolFactory?.(worker)) ?? [];

    const task: Task = {
      taskId: `worker-${randomUUID()}`,
      model: model.modelId,
      context: {
        systemPrompt: [
          `You are the "${worker.name}" worker.`,
          `Capability: ${worker.capability}`,
          `Step goal: ${assignment.step.orchestration.goal}`,
          purpose === "IMPLEMENT"
            ? "Complete the delegated implementation instruction."
            : "Verify and report a structured verdict.",
        ].join("\n"),
        messages: [{ role: "user", content: instruction }],
        toolNames: tools.map((t) => t.name),
        metadata: {
          orchestration: {
            stepId: assignment.step.id,
            workerName: worker.name,
            purpose,
          },
        },
      },
    };

    const result: TaskResult = await this.options.turnExecutor.execute(task, {
      model: resolved,
      tools,
      // Design §7.2: the worker's explicit iteration limit — never the
      // executor's default.
      maxIterationsOverride: assignment.step.orchestration.budget.maxWorkerIterations,
    });

    const callId = randomUUID();
    const usage = normalizeUsage(result.usage);
    if (result.status === "COMPLETE") {
      if (usage === null) {
        return this.failedOutcome(workerName, purpose, sequence, workspace, {
          code: "TOKEN_USAGE_UNAVAILABLE",
          message: "worker response lacked authoritative token usage",
        });
      }
      return {
        workerCall: {
          callId,
          workerName,
          purpose,
          status: "COMPLETED",
          startedSequence: sequence.startedSequence,
          completedSequence: sequence.completedSequence,
          workspaceRevisionBefore: workspace.revisionBefore,
          workspaceRevisionAfter: workspace.revisionAfter,
          tokenCount: usage.totalTokens,
        },
        summary: (result.completion ?? "").slice(0, 4000),
        tokenCount: usage.totalTokens,
      };
    }

    if (result.status === "CANCELLED") {
      return {
        workerCall: {
          callId,
          workerName,
          purpose,
          status: "CANCELLED",
          startedSequence: sequence.startedSequence,
          completedSequence: sequence.completedSequence,
          workspaceRevisionBefore: workspace.revisionBefore,
          workspaceRevisionAfter: workspace.revisionAfter,
          tokenCount: 0,
        },
        summary: "",
        tokenCount: 0,
      };
    }

    // FAILED: classify iteration cap vs provider failure.
    const finishReason = result.failure?.finishReason ?? "";
    const code: OrchestrationErrorCode =
      finishReason === "MAX_ITERATIONS" ? "WORKER_ITERATION_LIMIT" : "WORKER_FAILED";
    return this.failedOutcome(workerName, purpose, sequence, workspace, {
      code,
      message: result.failure?.message ?? "worker failed",
      tokenCount: usage?.totalTokens,
    });
  }

  private failedOutcome(
    workerName: string,
    purpose: "IMPLEMENT" | "VERIFY",
    sequence: { startedSequence: number; completedSequence: number },
    workspace: { revisionBefore: number; revisionAfter: number },
    failure: {
      code: OrchestrationErrorCode;
      message: string;
      tokenCount?: number;
    },
  ): InvokeWorkerOutcome {
    return {
      workerCall: {
        callId: randomUUID(),
        workerName,
        purpose,
        status: "FAILED",
        startedSequence: sequence.startedSequence,
        completedSequence: sequence.completedSequence,
        workspaceRevisionBefore: workspace.revisionBefore,
        workspaceRevisionAfter: workspace.revisionAfter,
        tokenCount: failure.tokenCount ?? 0,
        errorCode: failure.code,
      },
      summary: "",
      tokenCount: failure.tokenCount ?? 0,
      failure,
    };
  }
}