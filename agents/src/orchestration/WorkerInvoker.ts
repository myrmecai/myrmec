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
  VerifierResult,
} from "./types.js";
/** The tools a worker invocation gets: the workspace-scoped file/command
 * tools via the injected toolFactory (Feature 4), plus the runner-owned
 * `report_verdict` tool when purpose is VERIFY (Feature 5, design §11). */
export interface WorkerInvokerOptions {
  chatModelFactory: ChatModelFactory;
  turnExecutor: TurnExecutor;
  /** Optional per-worker tool injection (Feature 4 seam). */
  toolFactory?: (worker: WorkerAuthoring) => Promise<Tool[]>;
  /** Feature 5: records an accepted verdict into the ledger and returns
   * its runner-owned identity. Supplied by the runner per invocation;
   * VERIFY without it fails closed as INVALID_VERIFIER_RESULT. */
  verdictRecorder?: (
    input: Omit<VerifierResult, "attemptOrdinal" | "sequence">,
  ) => VerifierResult;
  /** Feature 5: the dispatch's immutable attempt order (§11 rule 4). */
  attemptOrdinal: number;
}

/** One worker invocation outcome with runner-owned identity. */
export interface InvokeWorkerOutcome {
  workerCall: WorkerCallResult;
  /** The worker's final answer text, bounded and redacted. */
  summary: string;
  /** Authoritative usage of the worker turn (design §13: normalized). */
  tokenCount: number;
  /** Design §10.1: a successful VERIFY call requires exactly one
   * verifierResult; other purposes prohibit it. */
  verifierResult?: VerifierResult;
  failure?: { code: OrchestrationErrorCode; message: string };
}

/** The structured verdict input (design §11). */
interface ReportVerdictInput {
  verdict: "APPROVED" | "REJECTED";
  summary: string;
  issues: string[];
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

  /** The runner injects the ledger-backed verdict recorder per run
   * (Feature 5, design §11 rules 3-4). */
  setVerdictRecorder(recorder: WorkerInvokerOptions["verdictRecorder"]): void {
    this.options.verdictRecorder = recorder;
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

    // Design §10.1: VERIFY is allowed only for a worker named in
    // requireVerificationBy; IMPLEMENT cannot be used to submit a verdict.
    if (purpose === "VERIFY") {
      const required = assignment.step.orchestration.completionCriteria.requireVerificationBy;
      if (!required.includes(workerName)) {
        return this.failedOutcome(workerName, purpose, sequence, workspace, {
          code: "ASSIGNMENT_VALIDATION_ERROR",
          message: `VERIFY is allowed only for required verifiers: ${workerName}`,
        });
      }
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

    // Feature 5 (design §11 rule 1-2): a VERIFY invocation carries the
    // runner-owned report_verdict tool; exactly one valid call is
    // required. A second call is rejected and cannot alter the first.
    let verdictRecord: VerifierResult | undefined;
    let verdictRejected = false;
    const reportVerdictTool: Tool | undefined =
      purpose === "VERIFY"
        ? {
            name: "report_verdict",
            description:
              "Report the structured verification verdict exactly once: " +
              'APPROVED or REJECTED with a bounded summary and issues.',
            parameters: {
              type: "object",
              properties: {
                verdict: { type: "string", enum: ["APPROVED", "REJECTED"] },
                summary: { type: "string" },
                issues: { type: "array", items: { type: "string" } },
              },
              required: ["verdict", "summary", "issues"],
            },
            invoke: async (rawArgs) => {
              if (verdictRecord || verdictRejected) {
                // Design §11 rule 2: more than one call is invalid and
                // the first record stands.
                return {
                  error: "INVALID_VERIFIER_RESULT",
                  message: "report_verdict was already called for this invocation",
                };
              }
              const args = rawArgs as unknown as ReportVerdictInput;
              if (
                (args.verdict !== "APPROVED" && args.verdict !== "REJECTED") ||
                typeof args.summary !== "string" ||
                !Array.isArray(args.issues) ||
                args.issues.some((i) => typeof i !== "string")
              ) {
                verdictRejected = true;
                return {
                  error: "INVALID_VERIFIER_RESULT",
                  message: "report_verdict requires verdict, summary, and issues",
                };
              }
              if (!this.options.verdictRecorder) {
                verdictRejected = true;
                return {
                  error: "INVALID_VERIFIER_RESULT",
                  message: "the runner did not supply a verdict recorder",
                };
              }
              verdictRecord = this.options.verdictRecorder({
                callId: randomUUID(),
                workerName,
                verdict: args.verdict,
                summary: args.summary.slice(0, 4000),
                issues: args.issues.map((i) => i.slice(0, 2000)).slice(0, 20),
                workspaceRevision: workspace.revisionAfter,
                candidateTreeHash: "", // the runner stamps the real tree hash
              });
              return {
                status: "RECORDED",
                verdict: verdictRecord.verdict,
                sequence: verdictRecord.sequence,
              };
            },
          }
        : undefined;
    const workerTools = [...tools, ...(reportVerdictTool ? [reportVerdictTool] : [])];

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
            : "Verify and report a structured verdict with report_verdict exactly once.",
        ].join("\n"),
        messages: [{ role: "user", content: instruction }],
        toolNames: workerTools.map((t) => t.name),
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
      tools: workerTools,
      // Design §7.2: the worker's explicit iteration limit — never the
      // executor's default.
      maxIterationsOverride: assignment.step.orchestration.budget.maxWorkerIterations,
    });

    const callId = randomUUID();
    const usage = normalizeUsage(result.usage);
    if (result.status === "COMPLETE") {
      // Design §11 rule 2: a verifier must call report_verdict exactly
      // once. Zero calls or an invalid one is INVALID_VERIFIER_RESULT.
      if (purpose === "VERIFY" && !verdictRecord) {
        return this.failedOutcome(workerName, purpose, sequence, workspace, {
          code: "INVALID_VERIFIER_RESULT",
          message: "the verifier completed without a valid report_verdict call",
          tokenCount: usage?.totalTokens,
        });
      }
      if (purpose === "IMPLEMENT" && verdictRecord) {
        return this.failedOutcome(workerName, purpose, sequence, workspace, {
          code: "INVALID_VERIFIER_RESULT",
          message: "an IMPLEMENT worker must not report a verdict",
          tokenCount: usage?.totalTokens,
        });
      }
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
        ...(verdictRecord ? { verifierResult: verdictRecord } : {}),
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