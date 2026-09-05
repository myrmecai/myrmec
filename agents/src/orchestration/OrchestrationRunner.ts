// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * OrchestrationRunner (design §8.4): owns one step's model loop,
 * delegation through the single `invoke_worker` tool, iteration limits,
 * and result assembly. Transport-independent: it consumes a validated
 * `OrchestrationAssignment` and returns a structured
 * `OrchestrationRunResult`. Expected execution failures are returned,
 * never thrown (§7.2 boundary rule).
 *
 * This feature (Feature 2) implements the minimal delegation loop:
 * one orchestrator, one worker at a time, no workspace mutation yet
 * (revisions stay at 0 until Feature 4 introduces real tools).
 */
import { createHash, randomUUID } from "node:crypto";
import type { TurnExecutor } from "../executor/TurnExecutor.js";
import type { Tool } from "../executor/types.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { ModelInfoWire } from "../protocol/taskFrames.js";
import type { Task, TaskResult } from "../models/index.js";
import type {
  OrchestrationAssignment,
  OrchestrationRunResult,
  WorkerCallResult,
  OrchestrationErrorCode,
} from "./types.js";
import { orchestrationAssignmentSchema } from "./schema.js";
import { normalizeUsage, WorkerInvoker } from "./WorkerInvoker.js";

export interface OrchestrationRunnerOptions {
  chatModelFactory: ChatModelFactory;
  workerInvoker: WorkerInvoker;
  turnExecutor: TurnExecutor;
}

export interface OrchestrationRunOptions {
  runId: string;
}

/** The orchestrator's single action tool input (design §10.1). */
interface InvokeWorkerInput {
  workerName: string;
  purpose: "IMPLEMENT" | "VERIFY";
  instruction: string;
}

export class OrchestrationRunner {
  private readonly options: OrchestrationRunnerOptions;

  constructor(options: OrchestrationRunnerOptions) {
    this.options = options;
  }

  /**
   * Execute exactly one validated orchestration step (design §5). The
   * orchestrator model plans and delegates through `invoke_worker`; the
   * runner owns every identity, sequence, and limit.
   */
  async run(
    assignmentInput: OrchestrationAssignment,
    options: OrchestrationRunOptions,
  ): Promise<OrchestrationRunResult> {
    void options; // reserved: cancellation/events land in later features
    // Boundary validation: the runtime never trusts unvalidated input.
    const parsed = orchestrationAssignmentSchema.safeParse(assignmentInput);
    if (!parsed.success) {
      return this.failure(
        assignmentInput.dispatch,
        "ASSIGNMENT_VALIDATION_ERROR",
        parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; "),
        { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
      );
    }
    const assignment = parsed.data as OrchestrationAssignment;
    const { step, dispatch } = assignment;
    const o = step.orchestration;

    let sequence = 0;
    const nextSequence = () => ++sequence;
    const workerCalls: WorkerCallResult[] = [];
    let totalTokens = 0;

    // Workspace revisions remain 0 until Feature 4's real mutating tools.
    const revision = { revisionBefore: 0, revisionAfter: 0 };

    const orchestratorModelDef = assignment.models.find((m) => m.code === o.modelCode);
    if (!orchestratorModelDef) {
      return this.failure(
        dispatch,
        "ASSIGNMENT_VALIDATION_ERROR",
        `orchestration model not in assignment: ${o.modelCode}`,
        resultUsage(0, workerCalls),
      );
    }

    const info: ModelInfoWire = {
      provider: orchestratorModelDef.provider,
      modelId: orchestratorModelDef.modelId,
      apiEndpoint: orchestratorModelDef.apiEndpoint,
      apiKey: null, // credentialRef resolution is the adapter's job
      parameters: orchestratorModelDef.parameters as Record<string, unknown>,
    };
    const orchestratorModel = await this.options.chatModelFactory.resolve(
      info,
      `orchestrator-${randomUUID()}`,
    );

    // The single action tool: validated mechanically before invocation.
    const invokeWorkerTool: Tool = {
      name: "invoke_worker",
      description:
        "Delegate a focused unit of work to a declared orchestration worker. " +
        "Worker names must come from the worker catalog. " +
        "purpose=IMPLEMENT delegates implementation; purpose=VERIFY delegates verification.",
      parameters: {
        type: "object",
        properties: {
          workerName: { type: "string" },
          purpose: { type: "string", enum: ["IMPLEMENT", "VERIFY"] },
          instruction: { type: "string" },
        },
        required: ["workerName", "purpose", "instruction"],
      },
      invoke: async (rawArgs) => {
        const args = rawArgs as unknown as InvokeWorkerInput;
        // Mechanical validation before any model call (design §10.1).
        if (
          typeof args.workerName !== "string" ||
          (args.purpose !== "IMPLEMENT" && args.purpose !== "VERIFY") ||
          typeof args.instruction !== "string"
        ) {
          return {
            status: "FAILED",
            errorCode: "ASSIGNMENT_VALIDATION_ERROR",
            summary: "invoke_worker requires workerName, purpose, and instruction.",
          };
        }
        if (args.purpose === "VERIFY") {
          // Feature 5 adds verifier verdict handling; until then VERIFY
          // through invoke_worker is not yet a supported completion path.
          return {
            status: "FAILED",
            errorCode: "INVALID_VERIFIER_RESULT",
            summary: "VERIFY purpose arrives with the verification feature.",
          };
        }
        const startedSequence = nextSequence();
        const outcome = await this.options.workerInvoker.invoke(
          assignment,
          args.workerName,
          args.purpose,
          args.instruction,
          { startedSequence, completedSequence: startedSequence },
          revision,
        );
        const completedSequence = nextSequence();
        // Stamp the runner-owned completion sequence onto the call record.
        outcome.workerCall.completedSequence = completedSequence;
        workerCalls.push(outcome.workerCall);
        if (outcome.workerCall.status === "COMPLETED") {
          totalTokens += outcome.tokenCount;
        }
        // The bounded, redacted InvokeWorkerResult (design §10.1): never
        // transcripts, prompts, file contents, diffs, or command output.
        const result: Record<string, unknown> = {
          callId: outcome.workerCall.callId,
          workerName: outcome.workerCall.workerName,
          purpose: outcome.workerCall.purpose,
          status: outcome.workerCall.status,
          summary: outcome.summary,
          workspaceRevisionBefore: outcome.workerCall.workspaceRevisionBefore,
          workspaceRevisionAfter: outcome.workerCall.workspaceRevisionAfter,
          tokenCount: outcome.workerCall.tokenCount,
        };
        if (outcome.workerCall.errorCode) {
          result.errorCode = outcome.workerCall.errorCode;
        }
        return result;
      },
    };

    // The orchestrator task (design §10.1 context).
    const task: Task = {
      taskId: `orchestrator-${randomUUID()}`,
      model: orchestratorModelDef.modelId,
      context: {
        systemPrompt: [
          "You are the orchestration coordinator for one workflow step.",
          `Step goal: ${o.goal}`,
          `Definition of done: ${o.completionCriteria.definitionOfDone}`,
          "",
          "Worker catalog (delegate with invoke_worker):",
          ...o.workers.map(
            (w) =>
              `- ${w.name} (${w.modelCode}): ${w.capability}; tools: ${w.allowedTools.join(", ") || "none"}; commands: ${w.allowedCommands.join(", ") || "none"}`,
          ),
          "",
          "Only delegated workers can inspect or change repository files. " +
            "You have no direct repository access. Delegate focused units " +
            "of work, review worker results, and finish with a summary of " +
            "the step outcome when the goal is met.",
        ].join("\n"),
        messages: [
          {
            role: "user",
            content: `Begin the step: ${o.goal}`,
          },
        ],
        toolNames: [invokeWorkerTool.name],
        metadata: { orchestration: { stepId: step.id } },
      },
    };

    const result: TaskResult = await this.options.turnExecutor.execute(task, {
      model: orchestratorModel,
      tools: [invokeWorkerTool],
      maxIterationsOverride: o.budget.maxOrchestratorIterations,
    });

    const usage = normalizeUsage(result.usage);
    totalTokens += usage?.totalTokens ?? 0;

    const usageOut = resultUsage(totalTokens, workerCalls);

    if (result.status === "FAILED") {
      // Orchestrator turn failed: classify iteration cap vs provider error.
      const finishReason = result.failure?.finishReason ?? "";
      const code: OrchestrationErrorCode =
        finishReason === "MAX_ITERATIONS"
          ? "ORCHESTRATOR_ITERATION_LIMIT"
          : "ORCHESTRATOR_FAILED";
      // A missing authoritative usage on a failed turn fails closed.
      if (code === "ORCHESTRATOR_ITERATION_LIMIT" && usage === null) {
        return this.failure(dispatch, "TOKEN_USAGE_UNAVAILABLE", "orchestration turn lacked authoritative token usage", usageOut, workerCalls);
      }
      return this.failure(dispatch, code, result.failure?.message ?? "orchestrator turn failed", usageOut, workerCalls);
    }
    if (result.status === "CANCELLED") {
      return {
        schemaVersion: "1.0",
        resultId: this.resultId(dispatch),
        resultDigest: this.resultDigest(dispatch),
        dispatch,
        status: "CANCELLED",
        retryDisposition: "NONE",
        summary: "",
        workerCalls,
        verifierResults: [],
        commandExecutions: [],
        changedFiles: [],
        commits: [],
        cleanWorktree: false,
        usage: usageOut,
      };
    }
    if (usage === null) {
      // A completed turn must still report authoritative usage.
      return this.failure(dispatch, "TOKEN_USAGE_UNAVAILABLE", "orchestration turn lacked authoritative token usage", usageOut, workerCalls);
    }

    return {
      schemaVersion: "1.0",
      resultId: this.resultId(dispatch),
      resultDigest: this.resultDigest(dispatch),
      dispatch,
      status: "COMPLETED",
      retryDisposition: "NONE",
      summary: (result.completion ?? "").slice(0, 4000),
      workerCalls,
      verifierResults: [],
      commandExecutions: [],
      changedFiles: [],
      commits: [],
      cleanWorktree: true,
      usage: usageOut,
    };
  }

  // ── result identity (§7.3: deterministic per dispatch+digest) ──────

  private resultId(dispatch: OrchestrationAssignment["dispatch"]): string {
    // Placeholder deterministic id: full UUIDv5 namespace pinning lands
    // with the engine-integration feature's shared constants module.
    const h = createHash("sha256")
      .update(`${dispatch.dispatchId}:${this.resultDigest(dispatch)}`)
      .digest("hex");
    return h.slice(0, 32);
  }

  private resultDigest(dispatch: OrchestrationAssignment["dispatch"]): string {
    return createHash("sha256")
      .update(`${dispatch.workflowId}:${dispatch.runId}:${dispatch.stepId}:${dispatch.dispatchId}`)
      .digest("hex")
      .slice(0, 32);
  }

  private failure(
    dispatch: OrchestrationAssignment["dispatch"],
    errorCode: OrchestrationErrorCode,
    message: string,
    usage: { workerCalls: number; rejectionCount: number; totalTokens: number },
    workerCalls: WorkerCallResult[] = [],
  ): OrchestrationRunResult {
    return {
      schemaVersion: "1.0",
      resultId: this.resultId(dispatch),
      resultDigest: this.resultDigest(dispatch),
      dispatch,
      status: "FAILED",
      retryDisposition: errorCode === "WORKER_FAILED" ? "RETRYABLE" : "TERMINAL",
      summary: message.slice(0, 4000),
      workerCalls,
      verifierResults: [],
      commandExecutions: [],
      changedFiles: [],
      commits: [],
      cleanWorktree: false,
      usage,
      errorCode,
    };
  }
}

function resultUsage(
  totalTokens: number,
  workerCalls: WorkerCallResult[],
): { workerCalls: number; rejectionCount: number; totalTokens: number } {
  return {
    workerCalls: workerCalls.length,
    rejectionCount: 0,
    totalTokens,
  };
}