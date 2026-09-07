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
import { InMemoryBudgetController, type BudgetBreach, type BudgetController } from "./BudgetController.js";
import { ORCHESTRATION_SCHEDULING_NS, uuidV5 } from "./constants.js";
import { governedActionDigest } from "./ApprovalPolicyEvaluator.js";
import { validateApprovalResume } from "./ApprovalResumeValidator.js";
import {
  InMemoryVerificationLedger,
  toVerifierResult,
  type VerificationLedger,
  type VerdictRecord,
} from "./VerificationLedger.js";
import type { StepWorkspace } from "../workspace/WorkspaceManager.js";
import type { WorkspaceInspector } from "../workspace/WorkspaceInspector.js";

export interface OrchestrationRunnerOptions {
  chatModelFactory: ChatModelFactory;
  workerInvoker: WorkerInvoker;
  turnExecutor: TurnExecutor;
  /** Feature 4: the resolved step workspace. When absent (Feature 2 unit
   * tests), revisions stay at 0 and workers get no tools. */
  workspace?: StepWorkspace;
  /** Feature 4: candidate-tree inspection after mutating calls. */
  workspaceInspector?: WorkspaceInspector;
  /** Trusted spec content loaded by runner code (design §5 step 4). */
  specification?: { content: string; sha256: string; byteLength: number };
  /** Feature 6: creates the verified checkpoint commit after the
   * completion criteria pass (design §12). Absent in unit tests without
   * a real workspace — no commit is attempted then. */
  checkpointService?: {
    create(
      approvedTreeHash: string,
      expectedHead: string,
    ): Promise<import("../workspace/GitCheckpointService.js").CheckpointOutcome>;
  };
  /** Feature 6: the head the run expects the target branch to sit at.
   * The adapter captures it at checkout; a moved ref fails closed. */
  expectedHead?: string;
  /** Feature 6: gitPolicy.allowCheckpoint gate (design §7). */
  allowCheckpoint?: boolean;
  /** Feature 7 (design §13): cooperative cancellation for the whole
   * dispatch. Checked before every side-effect boundary; once
   * cancelled no new model or tool call starts and no checkpoint
   * occurs. Cancellation never pretends to roll back a completed
   * external side effect — evidence is reported as-is. */
  cancellation?: { readonly cancelled: boolean };
  /** HITL (§17.4): the risk/policy evaluator. Absent with a policy that
   * would suspend → fail closed (the runner never executes an action
   * that requires approval without the ability to suspend durably). */
  approvalEvaluator?: {
    evaluate(
      assignment: OrchestrationAssignment,
      action: import("./GovernedAction.js").GovernedAction,
    ): import("./ApprovalPolicyEvaluator.js").ApprovalPolicyDecision;
    effectiveWorkerRisk(
      assignment: OrchestrationAssignment,
      worker: import("./types.js").WorkerAuthoring,
    ): "SAFE" | "DESTRUCTIVE" | "IRREVERSIBLE";
  };
  /** HITL (§17.4): the durable approval sink. REQUIRED when the
   * evaluator returns REQUIRE_APPROVAL — its absence fails closed with
   * APPROVAL_POLICY_DENIED (§17.4: "the runner must have an
   * OrchestrationApprovalSink; its absence fails closed"). */
  approvalSink?: import("./GovernedAction.js").OrchestrationApprovalSink;
  /** HITL (§17.4): suspension publication — the continuation store. The
   * runner publishes the continuation + suspension state BEFORE the
   * request and returns PAUSED only after the sink durably accepts it. */
  suspensionPublisher?: {
    publish(input: {
      dispatch: OrchestrationAssignment["dispatch"];
      action: import("./GovernedAction.js").GovernedAction;
      approvalRequestId: string;
      candidateTreeHash: string;
      workspaceRevision: number;
      expiresAt: string;
    }): Promise<{
      continuationId: string;
      continuationRef: string;
      snapshotTreeHash: string;
      stateDigest: string;
    }>;
  };
  /** HITL (§17.4, slice C): restores the stored suspension for a
   * decision-bearing continuation — the §7 validation binds the typed
   * decision against it before the runner resumes the exact pending
   * action. Absent with a decision-bearing continuation → fail closed. */
  suspensionLoader?: {
    load(
      continuationId: string,
    ): import("./ApprovalResumeValidator.js").RestoredSuspension | null;
  };
  /** §17.2/§17.5 (retry continuation): publishes the durable continuation
   * manifest after a WORKER_FAILED (RETRYABLE) safe boundary — completed
   * call identities, budget counters, verifier history, candidate tree.
   * The engine stores the returned §7.3 ContinuationRecord on the result;
   * a retry dispatch carries the continuationId and the runner restores
   * here. Absent on a retryable failure → the result fails closed (the
   * engine refuses a RETRYABLE without a continuation, §16.6). */
  retryContinuationPublisher?: {
    publish(input: {
      dispatchId: string;
      attemptOrdinal: number;
      budgetCounters: { workerCalls: number; totalTokens: number; rejectionCount: number };
      completedCalls: WorkerCallResult[];
      candidateTreeHash: string;
      workspaceRevision: number;
      verifierHistory: VerdictRecord[];
    }): Promise<{
      continuationId: string;
      continuationRef: string;
      stateDigest: string;
    }>;
  };
  /** §17.5 (retry restore): loads the published continuation manifest
   * for a decision-less continuation (engine retry) so the retried
   * dispatch deduplicates completed calls and restores the counters
   * (§13 same-dispatch restart semantics). */
  retryContinuationLoader?: {
    load(continuationId: string): import("./ContinuationStateStore.js").ContinuationManifest | null;
  };
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

/**
 * §17.4: thrown by the HITL gate when a worker invocation requires
 * approval. The run() body catches it, publishes the suspension, sends
 * the request through the durable sink, and returns PAUSED. Carries the
 * governed action + expiry — never raw tool arguments, source, diffs, or
 * credentials.
 */
class ApprovalRequiredSignal extends Error {
  constructor(
    readonly action: import("./GovernedAction.js").GovernedAction,
    readonly expiresAt: string,
  ) {
    super(`approval required for ${action.type} (${action.riskClass})`);
    this.name = "ApprovalRequiredSignal";
  }
}

/**
 * §17.4: thrown by the HITL gate on a profile/host DENY — terminal
 * APPROVAL_POLICY_DENIED; no approval request is created and the turn
 * must not keep planning around the denied action.
 */
class PolicyDeniedSignal extends Error {
  constructor(readonly reason: string) {
    super(reason);
    this.name = "PolicyDeniedSignal";
  }
}

export class OrchestrationRunner {
  private readonly options: OrchestrationRunnerOptions;
  /** §17.4 (slice C): the approved pending action's semantic identity
   * (type:riskClass:summary) — the gate matches THIS (a fresh attempt
   * derives a new attempt-local actionId, so the digest alone can never
   * match across attempts). */
  private approvedActionKey: string | null = null;

  constructor(options: OrchestrationRunnerOptions) {
    this.options = options;
  }

  /** The current workspace revision: increments only when the candidate
   * tree changes (design §11 rule 6). */
  private async refreshRevision(
    revision: { value: number },
    lastTree: { value: string },
  ): Promise<void> {
    const inspector = this.options.workspaceInspector;
    if (!inspector || !this.options.workspace) return;
    const candidate = await inspector.inspect(this.options.workspace);
    if (candidate.treeHash !== lastTree.value) {
      lastTree.value = candidate.treeHash;
      revision.value += 1;
    }
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

    // §7/§17.4 (HITL slice C): a decision-bearing continuation is a HITL
    // resume. The typed ApprovalDecision is validated against the
    // restored suspension BEFORE any work — identity, digests, status,
    // generation, and expiry all bind; any mismatch fails closed and no
    // pending action executes.
    const decision = assignment.continuation?.decision;
    if (decision) {
      const loader = this.options.suspensionLoader;
      if (!loader) {
        return this.failure(
          dispatch,
          "APPROVAL_POLICY_DENIED",
          "resume decision present but no suspension loader is wired — failing closed",
          { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
        );
      }
      const restored = loader.load(assignment.continuation!.continuationId);
      if (!restored) {
        return this.failure(
          dispatch,
          "APPROVAL_POLICY_DENIED",
          "resume decision for an unrestorable suspension — failing closed",
          { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
        );
      }
      const validation = validateApprovalResume(decision, restored);
      if (!validation.valid) {
        return this.failure(
          dispatch,
          validation.rejection ?? "APPROVAL_POLICY_DENIED",
          `resume decision rejected: ${validation.reason}`,
          { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
        );
      }
      // §17.4: "APPROVED authorizes only the stored pending action."
      // The validated decision authorizes the pending action's
      // SEMANTIC identity (type:riskClass:summary — the what), not the
      // attempt-local actionId (a fresh attempt derives a new id). The
      // HITL gate lets exactly that invocation through on this attempt;
      // every other action still evaluates normally.
      const pending = restored.suspension.pendingAction;
      this.approvedActionKey = pending
        ? `${pending.type}:${pending.riskClass}:${pending.summary}`
        : null;
    }

    // §17.5 (retry restore): a decision-less continuation is an engine
    // retry of a FAILED/RETRYABLE dispatch. The published manifest
    // restores the completed-call identities (replay dedup by callId),
    // the budget counters, and the verifier history (§13 same-dispatch
    // restart semantics — a crash cannot reset a budget). Absent
    // loader/manifest → the retry still runs, but with fresh state
    // (§13: a new attempt's counters; dedup simply finds nothing).
    let restoredManifest: import("./ContinuationStateStore.js").ContinuationManifest | null =
      null;
    if (assignment.continuation && !decision) {
      const retryLoader = this.options.retryContinuationLoader;
      restoredManifest = retryLoader
        ? retryLoader.load(assignment.continuation.continuationId)
        : null;
      // A retry continuation that cannot be restored fails closed — the
      // engine's §16.6 contract only retries with a valid continuation.
      if (!restoredManifest) {
        return this.failure(
          dispatch,
          "RECOVERY_SNAPSHOT_INVALID",
          `retry continuation ${assignment.continuation.continuationId} could not be restored — failing closed`,
          { workerCalls: 0, rejectionCount: 0, totalTokens: 0 },
        );
      }
    }

    let sequence = 0;
    const nextSequence = () => ++sequence;
    const workerCalls: WorkerCallResult[] = [];

    const revision = { value: 0 };
    const lastTree = { value: "" };
    // Baseline the candidate tree before any worker call so revisions
    // count only actual changes.
    await this.refreshRevision(revision, lastTree);

    // Feature 5: the per-attempt verification ledger (design §8.8). The
    // attempt ordinal comes from the validated dispatch (§11 rule 4) —
    // the model can never provide or influence it.
    const attemptOrdinal = dispatch.attemptOrdinal;
    const ledger: VerificationLedger = new InMemoryVerificationLedger();
    let rejectionCount = 0;
    const verifierResults: VerdictRecord[] = [];
    // Feature 7 (design §13): the per-attempt budget controller shared by
    // the orchestrator and every worker TurnExecutor. A new engine
    // attempt has a new dispatchId and FRESH counters (§17.2) — the
    // restored manifest contributes completed-call identities, candidate
    // state, and verifier history only, never counters.
    const budget: BudgetController = new InMemoryBudgetController(o.budget);
    // §17.5: the restored completed-call identities are available for
    // replay dedup at the §17.5 seam (same-dispatch restart); a NEW
    // engine attempt carries fresh callIds by design (§13), so no
    // invoke-path consultation applies in V1.
    if (restoredManifest) {
      void restoredManifest.completedCallIds;
    }
    // §17.5: replay the verifier history so the completion gate's
    // satisfies() sees the prior attempt's verdicts for the same tree.
    for (const record of restoredManifest?.verifierHistory ?? []) {
      ledger.record(record);
      verifierResults.push(record);
      if (record.verdict === "REJECTED") {
        rejectionCount += 1;
      }
    }
    let budgetBreach: BudgetBreach | null = null;
    const recordVerdict = (
      input: Omit<VerdictRecord, "sequence" | "attemptOrdinal">,
    ): VerdictRecord => {
      const record = ledger.record({
        ...input,
        attemptOrdinal,
        // Design §11 rule 3: the runner computes the candidate-tree hash
        // when accepting the verdict; the model cannot choose it.
        candidateTreeHash: lastTree.value,
      });
      verifierResults.push(record);
      if (record.verdict === "REJECTED") {
        rejectionCount += 1;
        // §13: stop immediately after exceeding the rejection budget —
        // the controller flags the breach and every later boundary
        // reports it.
        const breach = budget.recordRejection();
        if (breach) {
          budgetBreach = breach;
        }
      }
      return record;
    };
    this.options.workerInvoker.setVerdictRecorder(recordVerdict);
    // Feature 7 (§13): the worker turns share the per-attempt budget.
    this.options.workerInvoker.setBudget(budget);
    // Feature 7 (§13): the dispatch cancellation propagates into worker
    // turns (the orchestrator turn receives it via TurnRun).
    if (this.options.cancellation) {
      this.options.workerInvoker.setCancellation(this.options.cancellation);
    }

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

    // Feature 4/5: when a step workspace is provided, workers get their
    // declared tools through the WorkspaceToolFactory scoped to it
    // (design §10.3). Without a workspace (unit tests) the invoker's
    // toolFactory stays unset and workers have no tools.
    const commandExecutions: import("./types.js").CommandExecutionRecord[] = [];
    const toolFactory = this.options.workspace
      ? async (worker: import("./types.js").WorkerAuthoring): Promise<Tool[]> => {
          const { WorkspaceToolFactory } = await import("../tools/WorkspaceToolFactory.js");
          const factory = new WorkspaceToolFactory({
            workspace: this.options.workspace!,
            ...(this.options.specification
              ? {
                  specification: {
                    canonicalPath: `${this.options.workspace!.workingPath}/${assignment.step.orchestration.specPath ?? ""}`,
                    sha256: this.options.specification.sha256,
                    byteLength: this.options.specification.byteLength,
                  },
                }
              : {}),
            // Feature 5: the assignment's command templates (already
            // the referenced-subset) + per-run evidence collection.
            commandTemplates: assignment.policy.commandTemplates,
            recordExecution: (record) => commandExecutions.push(record),
            workerCallId: "",
          });
          return factory.resolve(worker);
        }
      : undefined;
    this.options.workerInvoker.setToolFactory(toolFactory);

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
          // Feature 5 (design §10.1): VERIFY delegates to a required
          // verifier; the verdict is recorded through the runner-owned
          // report_verdict tool inside the worker turn.
          const required = o.completionCriteria.requireVerificationBy;
          if (!required.includes(args.workerName)) {
            return {
              status: "FAILED",
              errorCode: "ASSIGNMENT_VALIDATION_ERROR",
              summary: `VERIFY is allowed only for required verifiers: ${args.workerName}`,
            };
          }
        }
        const startedSequence = nextSequence();
        const revisionBefore = revision.value;
        // §13 cancellation linearization point: once cancelled, no new
        // worker call starts.
        if (this.options.cancellation?.cancelled) {
          return { status: "CANCELLED", summary: "cancelled before worker invocation" };
        }
        // §17.4 HITL gate: evaluate the worker invocation BEFORE the
        // worker starts — and BEFORE the budget reservation, so a
        // suspended invocation never consumes a worker-call slot (the
        // resumed attempt gets fresh counters per §13). The pending
        // action is the whole WORKER_TOOL invocation; approval lets the
        // worker run to completion. The gate throws a typed signal that
        // the run() body catches to return PAUSED — a tool-result code
        // would let the orchestrator loop keep planning, which §17.4
        // forbids ("suspend once, before the worker starts").
        if (this.options.approvalEvaluator) {
          const worker = o.workers.find((w) => w.name === args.workerName);
          const riskClass = worker
            ? this.options.approvalEvaluator.effectiveWorkerRisk(assignment, worker)
            : "SAFE";
          const action: import("./GovernedAction.js").GovernedAction = {
            actionId: `action-${dispatch.dispatchId}-${startedSequence}`,
            type: "WORKER_TOOL",
            riskClass,
            summary: `worker:${args.workerName}:${args.purpose}`,
            digest: "",
          };
          action.digest = governedActionDigest(action);
          // §17.4: an APPROVED resume authorizes the exact pending
          // action — the gate matches the pending action's semantic
          // identity (the human already approved this invocation); every
          // other action evaluates normally.
          const actionKey = `${action.type}:${action.riskClass}:${action.summary}`;
          if (
            this.approvedActionKey !== null &&
            actionKey === this.approvedActionKey
          ) {
            // fall through to execution
          } else {
            const decision = this.options.approvalEvaluator.evaluate(assignment, action);
            if (decision.outcome === "DENY") {
              // §17.4: terminal — no request is created, and the turn must
              // NOT keep planning around a denied action.
              throw new PolicyDeniedSignal(decision.reason);
            }
            if (decision.outcome === "REQUIRE_APPROVAL") {
              throw new ApprovalRequiredSignal(action, decision.expiresAt);
            }
          }
        }
        // §13: count every accepted invoke_worker execution toward
        // maxWorkerCalls — a delegation rejected by the budget never
        // becomes an execution. A suspended invocation was never
        // accepted; only post-gate (approved) executions reserve.
        const callBreach = budget.tryReserveWorkerCall();
        if (callBreach) {
          budgetBreach = callBreach;
          return {
            status: "FAILED",
            errorCode: callBreach.errorCode,
            summary: callBreach.message,
          };
        }
        const outcome = await this.options.workerInvoker.invoke(
          assignment,
          args.workerName,
          args.purpose,
          args.instruction,
          { startedSequence, completedSequence: startedSequence },
          { revisionBefore, revisionAfter: revisionBefore },
        );
        const completedSequence = nextSequence();
        // After a mutating-capable call, recompute the candidate tree and
        // stamp the post-call revision (design §5 step 7).
        await this.refreshRevision(revision, lastTree);
        outcome.workerCall.workspaceRevisionBefore = revisionBefore;
        outcome.workerCall.workspaceRevisionAfter = revision.value;
        outcome.workerCall.completedSequence = completedSequence;
        workerCalls.push(outcome.workerCall);
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
        // Feature 5 (design §10.1): a successful VERIFY call carries
        // exactly one verifierResult; other purposes prohibit it.
        if (outcome.verifierResult) {
          result.verifierResult = toVerifierResult(outcome.verifierResult);
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
              `- ${w.name} (${w.modelCode}): ${w.capability}; tools: ${w.allowedTools.join(", ") || "none"}; commands: ${w.allowedCommands.join(", ") || "none"}` +
              (o.completionCriteria.requireVerificationBy.includes(w.name)
                ? " [required verifier]"
                : ""),
          ),
          "",
          o.completionCriteria.requireVerificationBy.length > 0
            ? `Required verifiers: ${o.completionCriteria.requireVerificationBy.join(", ")}. ` +
              "Every required verifier must approve the CURRENT candidate tree " +
              "before completion. A rejection must be repaired and reverified."
            : "",
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

    let result: TaskResult;
    try {
      result = await this.options.turnExecutor.execute(task, {
        model: orchestratorModel,
        tools: [invokeWorkerTool],
        maxIterationsOverride: o.budget.maxOrchestratorIterations,
        // §13: the orchestrator turn shares the per-attempt budget — its
        // responses are recorded and checked inside the model-tool loop.
        budget,
        // §13: the dispatch's cooperative cancellation propagates into
        // the orchestrator turn loop.
        ...(this.options.cancellation ? { cancellation: this.options.cancellation } : {}),
      });
    } catch (signal) {
      // §17.4: the HITL gate suspended a worker invocation. Publish the
      // continuation + suspension state FIRST, then send the idempotent
      // request through the durable sink, and only then return PAUSED.
      // The sink's absence fails closed (§17.4).
      if (signal instanceof ApprovalRequiredSignal) {
        const usageNow = {
          workerCalls: budget.counters().workerCalls,
          rejectionCount,
          totalTokens: budget.counters().totalTokens,
        };
        return await this.suspendForApproval(
          assignment,
          dispatch,
          signal,
          lastTree.value,
          revision.value,
          workerCalls,
          verifierResults,
          commandExecutions,
          usageNow,
        );
      }
      // §17.4: a profile/host DENY is terminal — the wire result keeps
      // the denial reason; no approval request exists.
      if (signal instanceof PolicyDeniedSignal) {
        return this.failure(
          dispatch,
          "APPROVAL_POLICY_DENIED",
          signal.reason,
          {
            workerCalls: budget.counters().workerCalls,
            rejectionCount,
            totalTokens: budget.counters().totalTokens,
          },
          workerCalls,
          verifierResults,
          commandExecutions,
        );
      }
      throw signal;
    }

    const usage = normalizeUsage(result.usage);

    // §13/§21: the result's usage mirrors the BudgetController's
    // authoritative counters — every orchestrator AND worker model
    // response counts toward maxTokens, so the evidence matches the
    // enforcement, not just the completed-call subset.
    const budgetCounters = budget.counters();
    const usageOut = {
      workerCalls: budgetCounters.workerCalls,
      rejectionCount,
      totalTokens: budgetCounters.totalTokens,
    };

    // §13: map a budget breach to the configured onBudgetExceeded action.
    const mapBreach = (breach: BudgetBreach): OrchestrationRunResult => {
      if (o.budget.onBudgetExceeded === "PAUSE_FOR_HUMAN_REVIEW") {
        // §7.3/§17.2: PAUSED requires retryDisposition NONE and a
        // suspension with a durable continuation reference. The local
        // continuation store persists the state; here the record binds
        // the continuation id and current candidate-tree state.
        const continuationId = `cont-${dispatch.dispatchId}-${attemptOrdinal}`;
        return {
          schemaVersion: "1.0",
          resultId: this.resultId(dispatch),
          resultDigest: this.resultDigest(dispatch),
          dispatch,
          status: "PAUSED",
          retryDisposition: "NONE",
          summary: breach.message.slice(0, 4000),
          workerCalls,
          verifierResults: verifierResults.map(toVerifierResult),
          commandExecutions,
          changedFiles: [],
          commits: [],
          cleanWorktree: false,
          usage: { ...usageOut, rejectionCount },
          errorCode: breach.errorCode,
          suspension: {
            // §7.3 ContinuationRecord subset the local store binds.
            continuationId,
            continuationRef: `local:${continuationId}`,
            snapshotTreeHash: lastTree.value,
            workspaceRevision: revision.value,
            stateDigest: createHash("sha256")
              .update(`${continuationId}:${lastTree.value}:${revision.value}`)
              .digest("hex"),
            reason: "BUDGET_REVIEW",
          },
        };
      }
      return this.failure(
        dispatch,
        breach.errorCode,
        breach.message,
        { ...usageOut, rejectionCount },
        workerCalls,
        verifierResults,
        commandExecutions,
      );
    };
    if (budgetBreach) {
      return mapBreach(budgetBreach);
    }

    if (result.status === "FAILED") {
      // Orchestrator turn failed: classify iteration cap vs provider
      // error vs budget exhaustion (the in-loop token check).
      const finishReason = result.failure?.finishReason ?? "";
      if (
        finishReason === "WORKER_BUDGET_EXCEEDED" ||
        finishReason === "TOKEN_BUDGET_EXCEEDED" ||
        finishReason === "REJECTION_BUDGET_EXCEEDED"
      ) {
        return mapBreach({
          checkpoint: "before-tool-execution",
          errorCode: finishReason,
          message: result.failure?.message ?? "budget exceeded",
        });
      }
      const code: OrchestrationErrorCode =
        finishReason === "MAX_ITERATIONS"
          ? "ORCHESTRATOR_ITERATION_LIMIT"
          : "ORCHESTRATOR_FAILED";
      // A missing authoritative usage on a failed turn fails closed.
      if (code === "ORCHESTRATOR_ITERATION_LIMIT" && usage === null) {
        return this.failure(dispatch, "TOKEN_USAGE_UNAVAILABLE", "orchestration turn lacked authoritative token usage", usageOut, workerCalls, verifierResults);
      }
      // §16.6: when the orchestrator cannot recover from a worker
      // invocation that failed with WORKER_FAILED, the RUN result
      // carries the worker's stable code — the §16.6 RETRYABLE
      // classification (engine retries within the step's maxRetries).
      const lastFailedWorkerCall = [...workerCalls]
        .reverse()
        .find((c) => c.status === "FAILED" && c.errorCode === "WORKER_FAILED");
      if (lastFailedWorkerCall) {
        // §17.2/§17.5: publish the durable retry continuation at this
        // safe boundary — completed-call identities, candidate state,
        // verifier history (fresh counters per §13 new-attempt rule).
        // The §7.3 ContinuationRecord rides the RETRYABLE result; the
        // engine stores it and the retry dispatch carries the
        // continuationId. Without a publisher the result stays RETRYABLE
        // but carries no continuation — the engine fails closed (§16.6).
        const publisher = this.options.retryContinuationPublisher;
        const published = publisher
          ? await publisher.publish({
              dispatchId: dispatch.dispatchId,
              attemptOrdinal,
              budgetCounters: budget.counters(),
              completedCalls: workerCalls,
              candidateTreeHash: lastTree.value,
              workspaceRevision: revision.value,
              verifierHistory: verifierResults,
            })
          : null;
        const failed = this.failure(dispatch, "WORKER_FAILED", result.failure?.message ?? "worker failed and the orchestrator did not recover", usageOut, workerCalls, verifierResults, commandExecutions);
        return published
          ? {
              ...failed,
              continuation: {
                continuationId: published.continuationId,
                continuationRef: published.continuationRef,
                snapshotTreeHash: lastTree.value,
                workspaceRevision: revision.value,
                stateDigest: published.stateDigest,
              },
            }
          : failed;
      }
      return this.failure(dispatch, code, result.failure?.message ?? "orchestrator turn failed", usageOut, workerCalls, verifierResults, commandExecutions);
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
        verifierResults: verifierResults.map(toVerifierResult),
        commandExecutions,
        changedFiles: [],
        commits: [],
        // §13/§18: evidence reports the real worktree state — a cancel
        // before any mutation leaves the committed baseline clean.
        cleanWorktree: await this.worktreeClean(),
        usage: { ...usageOut, rejectionCount },
      };
    }
    if (usage === null) {
      // A completed turn must still report authoritative usage.
      return this.failure(dispatch, "TOKEN_USAGE_UNAVAILABLE", "orchestration turn lacked authoritative token usage", usageOut, workerCalls);
    }

    // ── Completion gate (design §10.4) ────────────────────────────
    // COMPLETED requires an authoritative APPROVED record from every
    // required verifier for the exact current candidate tree (§11 rule
    // 11).
    const required = o.completionCriteria.requireVerificationBy;
    if (!ledger.satisfies(required, lastTree.value)) {
      return this.failure(
        dispatch,
        "INVALID_VERIFIER_RESULT",
        required.length === 0
          ? "no required verifiers"
          : `completion requires APPROVED verdicts from every required verifier for the current tree: ${required.join(", ")}`,
        { ...usageOut, rejectionCount },
        workerCalls,
        verifierResults,
        commandExecutions,
      );
    }

    // §13: before the checkpoint side effect — cancellation wins.
    if (this.options.cancellation?.cancelled) {
      return {
        schemaVersion: "1.0",
        resultId: this.resultId(dispatch),
        resultDigest: this.resultDigest(dispatch),
        dispatch,
        status: "CANCELLED",
        retryDisposition: "NONE",
        summary: "cancelled before checkpoint",
        workerCalls,
        verifierResults: verifierResults.map(toVerifierResult),
        commandExecutions,
        changedFiles: [],
        commits: [],
        // §13/§18: evidence reports the real worktree state.
        cleanWorktree: await this.worktreeClean(),
        usage: { ...usageOut, rejectionCount },
      };
    }

    // §10.4: the approved tree was committed successfully, or the
    // unchanged tree was accepted by allowNoChanges. The runner — never
    // a model — invokes the checkpoint service after the criteria pass.
    const commits: import("./types.js").CheckpointCommit[] = [];
    let cleanWorktree = true;
    if (this.options.checkpointService && this.options.allowCheckpoint !== false) {
      const checkpoint = await this.options.checkpointService.create(
        lastTree.value,
        this.options.expectedHead ?? "",
      );
      if (checkpoint.status === "COMMITTED") {
        commits.push(checkpoint.commit);
      } else if (checkpoint.status === "FAILED") {
        return this.failure(
          dispatch,
          checkpoint.errorCode,
          `checkpoint failed: ${checkpoint.errorCode}`,
          { ...usageOut, rejectionCount },
          workerCalls,
          verifierResults,
          commandExecutions,
        );
      }
      // NO_CHANGES: the unchanged tree was accepted (allowNoChanges)
      // — zero commits, and the §18 evidence is the ledger's APPROVED
      // records bound to the unchanged tree hash.
      // After a commit (or accepted no-op) the step scope must be clean.
      if (this.options.workspaceInspector && this.options.workspace) {
        const post = await this.options.workspaceInspector.inspect(this.options.workspace);
        cleanWorktree = post.clean;
      }
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
      verifierResults: verifierResults.map(toVerifierResult),
      commandExecutions,
      changedFiles: [],
      commits,
      cleanWorktree,
      usage: { ...usageOut, rejectionCount },
    };
  }

  // ── result identity (§7.3: deterministic per dispatch+digest) ──────

  /**
   * §17.4 HITL suspension: publish the continuation + suspension state,
   * send ONE idempotent request through the durable sink, and return
   * PAUSED only after the sink durably accepts it. No raw tool
   * arguments, source, diffs, or credentials cross the sink boundary.
   */
  private async suspendForApproval(
    _assignment: OrchestrationAssignment,
    dispatch: OrchestrationAssignment["dispatch"],
    signal: ApprovalRequiredSignal,
    candidateTreeHash: string,
    workspaceRevision: number,
    workerCalls: WorkerCallResult[],
    verifierRecords: VerdictRecord[],
    commandExecutions: import("./types.js").CommandExecutionRecord[],
    usage: { workerCalls: number; rejectionCount: number; totalTokens: number },
  ): Promise<OrchestrationRunResult> {
    const evaluator = this.options.approvalEvaluator;
    const sink = this.options.approvalSink;
    // §17.4 fail-closed: the gate only fires with an evaluator, but the
    // SINK must exist to suspend durably — its absence is terminal.
    if (!evaluator || !sink) {
      return this.failure(
        dispatch,
        "APPROVAL_POLICY_DENIED",
        "approval required but no OrchestrationApprovalSink is wired — failing closed",
        usage,
        workerCalls,
        verifierRecords,
        commandExecutions,
      );
    }

    // The §7.2/§16.3 approval request — idempotent by approvalRequestId;
    // the engine dedups on receipt. Derived BEFORE publication so the
    // persisted manifest binds the exact request identity.
    const approvalRequestId = uuidV5(
      ORCHESTRATION_SCHEDULING_NS,
      `approval:${dispatch.dispatchId}:${signal.action.actionId}`,
    );

    const publisher = this.options.suspensionPublisher;
    // §17.4: the runner first creates and publishes the continuation
    // (creating/reusing a private artifact only when reconstruction
    // requires it — V1 publishes the lightweight state; the artifact
    // seam lands with the recovery integration).
    const published = publisher
      ? await publisher.publish({
          dispatch,
          action: signal.action,
          approvalRequestId,
          candidateTreeHash,
          workspaceRevision,
          expiresAt: signal.expiresAt,
        })
      : {
          continuationId: `cont-${dispatch.dispatchId}-hitl`,
          continuationRef: `local:cont-${dispatch.dispatchId}-hitl`,
          snapshotTreeHash: candidateTreeHash,
          stateDigest: createHash("sha256")
            .update(`${dispatch.dispatchId}:${candidateTreeHash}:${workspaceRevision}:${signal.action.digest}`)
            .digest("hex"),
        };

    await sink.request({
      schemaVersion: "1.0",
      approvalRequestId,
      dispatch,
      action: signal.action,
      snapshotTreeHash: published.snapshotTreeHash,
      stateDigest: published.stateDigest,
      expiresAt: signal.expiresAt,
    });

    return {
      schemaVersion: "1.0",
      resultId: this.resultId(dispatch),
      resultDigest: this.resultDigest(dispatch),
      dispatch,
      status: "PAUSED",
      retryDisposition: "NONE",
      summary: `suspended for approval: ${signal.action.summary}`,
      workerCalls,
      verifierResults: verifierRecords.map(toVerifierResult),
      commandExecutions,
      changedFiles: [],
      commits: [],
      cleanWorktree: false,
      usage,
      suspension: {
        continuationId: published.continuationId,
        continuationRef: published.continuationRef,
        snapshotTreeHash: published.snapshotTreeHash,
        workspaceRevision,
        stateDigest: published.stateDigest,
        reason: "HITL_APPROVAL",
        approvalRequestId,
        pendingAction: signal.action,
        expiresAt: signal.expiresAt,
      },
    };
  }

  /**
   * The real worktree cleanliness (§13/§18): via the inspector when a
   * workspace is configured; true when no workspace exists (unit runs).
   */
  private async worktreeClean(): Promise<boolean> {
    if (!this.options.workspaceInspector || !this.options.workspace) {
      return true;
    }
    try {
      const candidate = await this.options.workspaceInspector.inspect(this.options.workspace);
      return candidate.clean;
    } catch {
      // An un-inspectable workspace reports not-clean — never a false
      // "clean" claim for evidence.
      return false;
    }
  }

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
    verifierRecords: VerdictRecord[] = [],
    commandExecutions: import("./types.js").CommandExecutionRecord[] = [],
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
      verifierResults: verifierRecords.map(toVerifierResult),
      commandExecutions,
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