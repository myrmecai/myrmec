// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * HelperInvoker (design §8.5): resolves one declared helper's model
 * through the ChatModelFactory and executes it through the existing
 * TurnExecutor. Rejects undeclared helpers, missing models, invalid
 * purposes, and tools outside policy — before model execution.
 */
import { randomUUID } from "node:crypto";
import type { TurnExecutor } from "../executor/TurnExecutor.js";
import type { Tool } from "../executor/types.js";
import type { ChatModelFactory } from "../executor/providers.js";
import type { ModelInfoWire } from "../protocol/sessionTypes.js";
import type { Task, TaskResult } from "../models/index.js";
import type {
  OrchestrationAssignment,
  HelperAuthoring,
  HelperCallResult,
  OrchestrationErrorCode,
  VerifierResult,
} from "./types.js";
import type { BudgetController } from "./BudgetController.js";
/** The tools a helper invocation gets: the workspace-scoped file/command
 * tools via the injected toolFactory (Feature 4), plus the runner-owned
 * `report_verdict` tool when purpose is VERIFY (Feature 5, design §11). */
export interface HelperInvokerOptions {
  chatModelFactory: ChatModelFactory;
  turnExecutor: TurnExecutor;
  /** Optional per-helper tool injection (Feature 4 seam). */
  toolFactory?: (helper: HelperAuthoring) => Promise<Tool[]>;
  /** Feature 5: records an accepted verdict into the ledger and returns
   * its runner-owned identity. Supplied by the runner per invocation;
   * VERIFY without it fails closed as INVALID_VERIFIER_RESULT. */
  verdictRecorder?: (
    input: Omit<VerifierResult, "attemptOrdinal" | "sequence">,
  ) => VerifierResult;
  /** Feature 5: the dispatch's immutable attempt order (§11 rule 4). */
  attemptOrdinal: number;
  /** Feature 7 (design §13): the shared per-attempt budget — a helper
   * response that crosses the token limit has its tool calls skipped
   * and the invocation is classified as a budget failure. */
  budget?: BudgetController;
}

/** One helper invocation outcome with runner-owned identity. */
export interface InvokeHelperOutcome {
  helperCall: HelperCallResult;
  /** The helper's final answer text, bounded and redacted. */
  summary: string;
  /** Authoritative usage of the helper turn (design §13: normalized). */
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

// ── normalizeUsage ───────────────────────────────────────────────────

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

// ── HelperInvoker ────────────────────────────────────────────────────

export class HelperInvoker {
  private readonly options: HelperInvokerOptions;
  /** Per-run tool factory injected by the runner (Feature 4): builds the
   * helper's declared tools scoped to the step workspace. */
  private toolFactory?: HelperInvokerOptions["toolFactory"];

  constructor(options: HelperInvokerOptions) {
    this.options = options;
  }

  /** The runner injects the workspace-scoped tool factory per run. */
  setToolFactory(toolFactory: HelperInvokerOptions["toolFactory"]): void {
    this.toolFactory = toolFactory ?? this.options.toolFactory;
  }

  /** The runner injects the ledger-backed verdict recorder per run
   * (Feature 5, design §11 rules 3-4). */
  setVerdictRecorder(recorder: HelperInvokerOptions["verdictRecorder"]): void {
    this.options.verdictRecorder = recorder;
  }

  /** Feature 7 (§13): the runner injects the shared per-attempt budget. */
  setBudget(budget: BudgetController): void {
    this.options.budget = budget;
  }

  /** Feature 7 (§13): the runner injects the dispatch cancellation. */
  setCancellation(cancellation: { readonly cancelled: boolean }): void {
    this.cancellation = cancellation;
  }

  /** The dispatch's cooperative cancellation signal (§13). */
  private cancellation?: { readonly cancelled: boolean };

  /**
   * Invoke one declared helper. Validates the declaration and the model
   * catalog entry before any model execution; expected execution failures
   * are returned, not thrown (design §7.2 boundary rule).
   */
  async invoke(
    assignment: OrchestrationAssignment,
    helperName: string,
    purpose: "IMPLEMENT" | "VERIFY",
    instruction: string,
    sequence: { startedSequence: number; completedSequence: number },
    workspace: { revisionBefore: number; revisionAfter: number },
  ): Promise<InvokeHelperOutcome> {
    const helper = assignment.step.orchestration.helpers.find((w) => w.name === helperName);
    if (!helper) {
      return this.failedOutcome(helperName, purpose, sequence, workspace, {
        code: "ASSIGNMENT_VALIDATION_ERROR",
        message: `undeclared helper: ${helperName}`,
      });
    }

    // Design §10.1: VERIFY is allowed only for a helper named in
    // requireVerificationBy; IMPLEMENT cannot be used to submit a verdict.
    if (purpose === "VERIFY") {
      const required = assignment.step.orchestration.completionCriteria.requireVerificationBy;
      if (!required.includes(helperName)) {
        return this.failedOutcome(helperName, purpose, sequence, workspace, {
          code: "ASSIGNMENT_VALIDATION_ERROR",
          message: `VERIFY is allowed only for required verifiers: ${helperName}`,
        });
      }
    }

    const model = assignment.models.find((m) => m.code === helper.modelCode);
    if (!model) {
      return this.failedOutcome(helperName, purpose, sequence, workspace, {
        code: "ASSIGNMENT_VALIDATION_ERROR",
        message: `helper model not in assignment: ${helper.modelCode}`,
      });
    }

    const info: ModelInfoWire = {
      provider: model.provider,
      modelId: model.modelId,
      endpoint: model.endpoint,
      // Credential-envelope delivery (design §9): the assignment carries an
      // adapter-scoped ref only; the plaintext never reaches the invoker.
      credentialRef: null,
      parameters: model.parameters as Record<string, unknown>,
    };
    const resolved = await this.options.chatModelFactory.resolve(info, `helper-${randomUUID()}`);

    const tools = (await this.toolFactory?.(helper)) ?? [];

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
                helperName,
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
    const helperTools = [...tools, ...(reportVerdictTool ? [reportVerdictTool] : [])];

    const task: Task = {
      taskId: `helper-${randomUUID()}`,
      model: model.modelId,
      context: {
        systemPrompt: [
          `You are the "${helper.name}" helper.`,
          `Capability: ${helper.capability}`,
          `Step goal: ${assignment.step.orchestration.goal}`,
          purpose === "IMPLEMENT"
            ? "Complete the delegated implementation instruction."
            : "Verify and report a structured verdict with report_verdict exactly once.",
        ].join("\n"),
        messages: [{ role: "user", content: instruction }],
        toolNames: helperTools.map((t) => t.name),
        metadata: {
          orchestration: {
            stepId: assignment.step.id,
            helperName: helper.name,
            purpose,
          },
        },
      },
    };

    const result: TaskResult = await this.options.turnExecutor.execute(task, {
      model: resolved,
      tools: helperTools,
      // Design §7.2: the helper's explicit iteration limit — never the
      // executor's default.
      maxIterationsOverride: assignment.step.orchestration.budget.maxWorkerIterations,
      // §13: the helper turn shares the per-attempt budget.
      ...(this.options.budget ? { budget: this.options.budget } : {}),
      // §13: the dispatch's cancellation propagates into the helper loop.
      ...(this.cancellation ? { cancellation: this.cancellation } : {}),
    });

    const callId = randomUUID();
    const usage = normalizeUsage(result.usage);
    if (result.status === "COMPLETE") {
      // Design §11 rule 2: a verifier must call report_verdict exactly
      // once. Zero calls or an invalid one is INVALID_VERIFIER_RESULT.
      if (purpose === "VERIFY" && !verdictRecord) {
        return this.failedOutcome(helperName, purpose, sequence, workspace, {
          code: "INVALID_VERIFIER_RESULT",
          message: "the verifier completed without a valid report_verdict call",
          tokenCount: usage?.totalTokens,
        });
      }
      if (purpose === "IMPLEMENT" && verdictRecord) {
        return this.failedOutcome(helperName, purpose, sequence, workspace, {
          code: "INVALID_VERIFIER_RESULT",
          message: "an IMPLEMENT helper must not report a verdict",
          tokenCount: usage?.totalTokens,
        });
      }
      if (usage === null) {
        return this.failedOutcome(helperName, purpose, sequence, workspace, {
          code: "TOKEN_USAGE_UNAVAILABLE",
          message: "helper response lacked authoritative token usage",
        });
      }
      return {
        helperCall: {
          callId,
          helperName,
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
        helperCall: {
          callId,
          helperName,
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

    // FAILED: classify iteration cap vs provider failure vs budget
    // exhaustion (§13 — the in-loop token check).
    const finishReason = result.failure?.finishReason ?? "";
    if (
      finishReason === "HELPER_BUDGET_EXCEEDED" ||
      finishReason === "TOKEN_BUDGET_EXCEEDED" ||
      finishReason === "REJECTION_BUDGET_EXCEEDED"
    ) {
      return this.failedOutcome(helperName, purpose, sequence, workspace, {
        code: finishReason,
        message: result.failure?.message ?? "budget exceeded",
        tokenCount: usage?.totalTokens,
      });
    }
    const code: OrchestrationErrorCode =
      finishReason === "MAX_ITERATIONS" ? "HELPER_ITERATION_LIMIT" : "HELPER_FAILED";
    return this.failedOutcome(helperName, purpose, sequence, workspace, {
      code,
      message: result.failure?.message ?? "helper failed",
      tokenCount: usage?.totalTokens,
    });
  }

  private failedOutcome(
    helperName: string,
    purpose: "IMPLEMENT" | "VERIFY",
    sequence: { startedSequence: number; completedSequence: number },
    workspace: { revisionBefore: number; revisionAfter: number },
    failure: {
      code: OrchestrationErrorCode;
      message: string;
      tokenCount?: number;
    },
  ): InvokeHelperOutcome {
    return {
      helperCall: {
        callId: randomUUID(),
        helperName,
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