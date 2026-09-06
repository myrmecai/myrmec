// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Contract tests for the minimal delegation loop (design §10.1): one
 * orchestrator TurnExecutor whose only tool is `invoke_worker`, backed
 * by the WorkerInvoker through the ChatModelFactory/TurnExecutor seams.
 */
import { describe, it, expect } from "vitest";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ToolSpec,
} from "../executor/types.js";
import { WorkerInvoker } from "./WorkerInvoker.js";
import { OrchestrationRunner } from "./OrchestrationRunner.js";
import type { OrchestrationAssignment } from "./types.js";

// ── helpers ──────────────────────────────────────────────────────────

/** Scripted ChatModel: returns queued responses in order. */
class ScriptedModel implements ChatModel {
  private queue: ModelResponse[];
  readonly invocations: { tools: ToolSpec[] }[] = [];
  constructor(responses: ModelResponse[]) {
    this.queue = [...responses];
  }
  async invoke(_messages: ConversationMessage[], tools: ToolSpec[]): Promise<ModelResponse> {
    this.invocations.push({ tools });
    const next = this.queue.shift();
    if (!next) throw new Error("scripted model exhausted");
    return next;
  }
}

/** Minimal assignment per design §7 with one coder worker. */
function assignment(): OrchestrationAssignment {
  return {
    schemaVersion: "1.0",
    dispatch: {
      workflowId: "wf-uuid",
      runId: "run-uuid",
      stepId: "step-1",
      taskId: "task-uuid",
      attemptId: "attempt-uuid",
      attemptOrdinal: 1,
      dispatchId: "attempt-uuid",
    },
    models: [
      {
        code: "orch-model",
        provider: "stub",
        modelId: "orch-model",
        description: "orchestrator",
        apiEndpoint: null,
        credentialRef: null,
        parameters: {},
      },
      {
        code: "worker-model",
        provider: "stub",
        modelId: "worker-model",
        description: "worker",
        apiEndpoint: null,
        credentialRef: null,
        parameters: {},
      },
    ],
    source: {
      repoUrl: "https://example.com/r.git",
      sourceBranch: "main",
      sourceBaseCommit: "a".repeat(40),
      targetBranch: "feat/t",
      credentialRef: null,
    },
    policy: {
      allowedTools: ["read_file", "write_file"],
      commandTemplates: {},
      requiredIsolation: "TRUSTED_PROCESS",
      approvalPolicy: {},
      gitPolicy: { allowCheckpoint: true, allowPush: false },
      workspaceRetentionSeconds: 3600,
    },
    step: {
      id: "step-1",
      name: "Step one",
      taskType: "ORCHESTRATOR",
      agentProfileCode: "governed-coding",
      dependsOn: [],
      retryPolicy: { maxRetries: 1, initialBackoffSeconds: 2, maxBackoffSeconds: 30 },
      orchestration: {
        modelCode: "orch-model",
        goal: "Build a feature.",
        specPath: null,
        sourceSubPath: "app",
        workers: [
          {
            name: "coder",
            modelCode: "worker-model",
            capability: "Writes code",
            allowedTools: ["read_file", "write_file"],
            allowedCommands: [],
          },
        ],
        checkpointStrategy: {
          mode: "ON_VERIFICATION_PASS",
          commitMessage: "feat: test",
          pushToRemote: false,
          allowNoChanges: false,
        },
        completionCriteria: {
          definitionOfDone: "Feature built.",
          requireVerificationBy: [],
        },
        budget: {
          maxTokens: 100000,
          maxWorkerCalls: 10,
          maxVerifierRejectionsPerAttempt: 3,
          maxOrchestratorIterations: 20,
          maxWorkerIterations: 10,
          onBudgetExceeded: "FAIL",
        },
      },
    },
  };
}

/** Build a runner whose models are scripted. */
function makeRunner(
  orchestratorModel: ChatModel,
  workerModel: ChatModel,
): OrchestrationRunner {
  const invoker = new WorkerInvoker({
    attemptOrdinal: 1,
    chatModelFactory: {
      resolve: async (info) => {
        if (info.modelId === "worker-model") return workerModel;
        throw new Error(`unexpected model: ${info.modelId}`);
      },
    },
    turnExecutor: new TurnExecutor({}),
  });
  const runner = new OrchestrationRunner({
    chatModelFactory: {
      resolve: async (info) => {
        if (info.modelId === "orch-model") return orchestratorModel;
        throw new Error(`unexpected model: ${info.modelId}`);
      },
    },
    workerInvoker: invoker,
    turnExecutor: new TurnExecutor({}),
  });
  return runner;
}

/** A worker invocation tool call the orchestrator makes. */
function invokeCall(workerName: string, instruction = "Do the work."): ModelResponse {
  return {
    content: "",
    toolCalls: [
      {
        id: "call-1",
        name: "invoke_worker",
        args: { workerName, purpose: "IMPLEMENT", instruction },
      },
    ],
    usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
  };
}

// ── the minimal delegation loop ──────────────────────────────────────

describe("OrchestrationRunner minimal delegation", () => {
  it("delegates to the named worker, feeds the result back, and completes", async () => {
    // Orchestrator: invoke coder, then final answer.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The coder completed the work.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    // Worker: immediately returns a final answer.
    const worker = new ScriptedModel([
      {
        content: "Implementation done.",
        usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 },
      },
    ]);
    const result = await makeRunner(orch, worker).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("COMPLETED");
    expect(result.summary).toBe("The coder completed the work.");
    expect(result.workerCalls).toHaveLength(1);
    const call = result.workerCalls[0];
    expect(call.workerName).toBe("coder");
    expect(call.purpose).toBe("IMPLEMENT");
    expect(call.status).toBe("COMPLETED");
    expect(call.tokenCount).toBe(40);
    // Runner-owned identity, not model-supplied.
    expect(call.callId).toBeTruthy();
    expect(call.workspaceRevisionBefore).toBeDefined();
    expect(call.workspaceRevisionAfter).toBeDefined();
    // §13: the usage mirrors the budget counters — every orchestrator
    // AND worker model response's tokens.
    expect(result.usage.totalTokens).toBe(15 + 28 + 40);
    expect(result.usage.workerCalls).toBe(1);
    expect(result.errorCode).toBeUndefined();
  });

  it("rejects an undeclared worker name", async () => {
    const orch = new ScriptedModel([invokeCall("ghost")]);
    const worker = new ScriptedModel([]);

    const result = await makeRunner(orch, worker).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    // The tool failure is fed back; the orchestrator eventually fails or
    // retries. With the scripted single response exhausted, the runner
    // reports a failure — the exact code is WORKER_FAILED-family.
    expect(result.errorCode).toBeTruthy();
  });

  it("rejects a worker model code absent from assignment.models", async () => {
    const a = assignment();
    // Remove the worker model from the catalog but keep the reference.
    a.models = a.models.filter((m) => m.code !== "worker-model");
    const orch = new ScriptedModel([invokeCall("coder")]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => {
            throw new Error("must not be called");
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });

    const result = await runner.run(a, { runId: "run-uuid" });
    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ASSIGNMENT_VALIDATION_ERROR");
  });

  it("feeds a worker failure back to the orchestrator; a recovered run completes", async () => {
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The worker failed; stopping.",
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
    ]);
    class FailingModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("provider exploded");
      }
    }

    const result = await makeRunner(orch, new FailingModel()).run(assignment(), {
      runId: "run-uuid",
    });

    // Design §10.1: expected worker failure is returned as a FAILED tool
    // result and fed back; the orchestrator recovered and finished.
    expect(result.status).toBe("COMPLETED");
    expect(result.summary).toBe("The worker failed; stopping.");
    expect(result.workerCalls[0].status).toBe("FAILED");
    expect(result.workerCalls[0].errorCode).toBe("WORKER_FAILED");
  });

  it("returns WORKER_FAILED when the orchestrator cannot recover from the worker failure", async () => {
    // The orchestrator's turn also fails after the worker failure.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      // Second response also requests the (failing) worker.
      invokeCall("coder"),
      // Then a final failure: the scripted model is exhausted, which throws.
    ]);
    class FailingModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("provider exploded");
      }
    }
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => new FailingModel(),
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.workerCalls.length).toBeGreaterThan(0);
    expect(result.workerCalls[0].status).toBe("FAILED");
    expect(result.workerCalls[0].errorCode).toBe("WORKER_FAILED");
  });

  it("enforces maxOrchestratorIterations independent of model output", async () => {
    // Orchestrator loops requesting the worker forever. The budget
    // limits are raised so ONLY the iteration cap terminates the loop.
    const a = assignment();
    a.step.orchestration.budget.maxWorkerCalls = 100;
    a.step.orchestration.budget.maxTokens = 100000;
    const loop: ModelResponse[] = Array.from({ length: 30 }, () => invokeCall("coder"));
    const orch = new ScriptedModel(loop);
    const worker = new ScriptedModel(
      Array.from({ length: 30 }, () => ({
        content: "done again",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      })),
    );

    const result = await makeRunner(orch, worker).run(a, {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_ITERATION_LIMIT");
  });

  it("fails with TOKEN_USAGE_UNAVAILABLE for a missing orchestration usage", async () => {
    // Orchestrator returns a final answer with NO usage block.
    const orch = new ScriptedModel([{ content: "Done." }]);
    const worker = new ScriptedModel([]);

    const result = await makeRunner(orch, worker).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("TOKEN_USAGE_UNAVAILABLE");
  });

  it("returns ORCHESTRATOR_FAILED when the orchestrator model itself fails", async () => {
    class FailingOrchModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("orchestrator provider down");
      }
    }
    const worker = new ScriptedModel([]);

    const result = await makeRunner(new FailingOrchModel(), worker).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_FAILED");
  });
});

// ── verification (Feature 5, design §11) ──────────────────────────────

/** An assignment whose completion requires a named verifier. */
function verifierAssignment(): OrchestrationAssignment {
  const a = assignment();
  a.step.orchestration.workers.push({
    name: "verifier",
    modelCode: "worker-model",
    capability: "Reviews code",
    allowedTools: ["read_file"],
    allowedCommands: [],
  });
  a.step.orchestration.completionCriteria.requireVerificationBy = ["verifier"];
  return a;
}

/** A VERIFY delegation the orchestrator makes. */
function verifyCall(workerName: string): ModelResponse {
  return {
    content: "",
    toolCalls: [
      {
        id: "call-v",
        name: "invoke_worker",
        args: { workerName, purpose: "VERIFY", instruction: "Verify the work." },
      },
    ],
    usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
  };
}

describe("OrchestrationRunner verification", () => {
  it("completes when the required verifier approves through report_verdict", async () => {
    // Orchestrator: delegate coder, verify, finish.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      verifyCall("verifier"),
      {
        content: "Implemented and verified.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    // Coder worker: no-op final answer.
    const coder = new ScriptedModel([
      { content: "did it", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);
    // Verifier worker: call report_verdict once, then finish.
    const verifier = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "call-v1",
            name: "report_verdict",
            args: { verdict: "APPROVED", summary: "Looks good", issues: [] },
          },
        ],
        usage: { promptTokens: 12, completionTokens: 6, totalTokens: 18 },
      },
      { content: "verified", usage: { promptTokens: 4, completionTokens: 2, totalTokens: 6 } },
    ]);

    // Both workers resolve through "worker-model": the coder is invoked
    // first, the verifier second — serve from a queue.
    const workerQueue: ChatModel[] = [coder, verifier];
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          const next = workerQueue.shift();
          if (!next) throw new Error("worker queue exhausted");
          return next;
        },
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => {
            const next = workerQueue.shift();
            if (!next) throw new Error("worker queue exhausted");
            return next;
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("COMPLETED");
    expect(result.verifierResults).toHaveLength(1);
    expect(result.verifierResults[0].verdict).toBe("APPROVED");
    expect(result.verifierResults[0].workerName).toBe("verifier");
    expect(result.verifierResults[0].attemptOrdinal).toBe(1);
    expect(result.usage.rejectionCount).toBe(0);
  });

  it("rejects completion when the orchestrator never delegates verification", async () => {
    // The orchestrator claims completion without invoking the verifier.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "Done, skipping verification.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const coder = new ScriptedModel([
      { content: "did it", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) =>
          info.modelId === "orch-model" ? orch : coder,
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => coder },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("INVALID_VERIFIER_RESULT");
  });

  it("records a rejection and blocks completion until a fresh APPROVED verdict", async () => {
    // Orchestrator: verify (rejected), then verify again (approved).
    const orch = new ScriptedModel([
      verifyCall("verifier"),
      verifyCall("verifier"),
      {
        content: "Repaired and reverified.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const rejectThenApprove = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "vr1",
            name: "report_verdict",
            args: { verdict: "REJECTED", summary: "missing tests", issues: ["no tests"] },
          },
        ],
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
      { content: "rejected", usage: { promptTokens: 4, completionTokens: 2, totalTokens: 6 } },
      {
        content: "",
        toolCalls: [
          {
            id: "vr2",
            name: "report_verdict",
            args: { verdict: "APPROVED", summary: "fixed", issues: [] },
          },
        ],
        usage: { promptTokens: 12, completionTokens: 6, totalTokens: 18 },
      },
      { content: "approved", usage: { promptTokens: 5, completionTokens: 3, totalTokens: 8 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => (info.modelId === "orch-model" ? orch : rejectThenApprove),
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => rejectThenApprove },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("COMPLETED");
    // Rule 7: both records remain in the audit history.
    expect(result.verifierResults).toHaveLength(2);
    expect(result.verifierResults[0].verdict).toBe("REJECTED");
    expect(result.verifierResults[1].verdict).toBe("APPROVED");
    expect(result.usage.rejectionCount).toBe(1);
  });

  it("fails a verifier that completes without calling report_verdict", async () => {
    // The verifier model never calls report_verdict.
    const orch = new ScriptedModel([
      verifyCall("verifier"),
      {
        content: "Verified.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const lazyVerifier = new ScriptedModel([
      { content: "seems fine", usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => (info.modelId === "orch-model" ? orch : lazyVerifier),
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => lazyVerifier },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    // The worker call FAILED with INVALID_VERIFIER_RESULT and was fed
    // back; the orchestrator finished anyway, so the completion gate
    // fails with INVALID_VERIFIER_RESULT (no APPROVED record exists).
    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("INVALID_VERIFIER_RESULT");
    expect(result.workerCalls[0].status).toBe("FAILED");
    expect(result.workerCalls[0].errorCode).toBe("INVALID_VERIFIER_RESULT");
  });

  it("fails a verifier that calls report_verdict twice (first record stands)", async () => {
    const orch = new ScriptedModel([
      verifyCall("verifier"),
      {
        content: "Verified.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const doubleVerifier = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "vd1",
            name: "report_verdict",
            args: { verdict: "REJECTED", summary: "bad", issues: ["x"] },
          },
          {
            id: "vd2",
            name: "report_verdict",
            args: { verdict: "APPROVED", summary: "changed my mind", issues: [] },
          },
        ],
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
      { content: "done", usage: { promptTokens: 4, completionTokens: 2, totalTokens: 6 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => (info.modelId === "orch-model" ? orch : doubleVerifier),
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => doubleVerifier },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    // Rule 2: the second call cannot alter the first record — the
    // rejection stands, so completion is blocked.
    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("INVALID_VERIFIER_RESULT");
    // Exactly one record was accepted.
    expect(result.verifierResults).toHaveLength(1);
    expect(result.verifierResults[0].verdict).toBe("REJECTED");
  });

  it("rejects a VERIFY delegation to a worker not in requireVerificationBy", async () => {
    const orch = new ScriptedModel([
      // coder is a declared worker but NOT a required verifier.
      {
        content: "",
        toolCalls: [
          {
            id: "call-bad",
            name: "invoke_worker",
            args: { workerName: "coder", purpose: "VERIFY", instruction: "verify" },
          },
        ],
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
      {
        content: "Done.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const coder = new ScriptedModel([
      { content: "did it", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => (info.modelId === "orch-model" ? orch : coder),
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => coder },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("INVALID_VERIFIER_RESULT");
  });
});

// ── budgets (Feature 7, design §13) ──────────────────────────────────

describe("OrchestrationRunner budget enforcement", () => {
  it("stops at maxWorkerCalls with WORKER_BUDGET_EXCEEDED (FAIL)", async () => {
    const a = assignment();
    a.step.orchestration.budget.maxWorkerCalls = 1;
    a.step.orchestration.budget.onBudgetExceeded = "FAIL";
    // Orchestrator delegates twice; the second must be rejected.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      invokeCall("coder"),
      {
        content: "done",
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
    ]);
    const worker = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const result = await makeRunner(orch, worker).run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
    expect(result.retryDisposition).toBe("TERMINAL");
    // Exactly one worker call executed; the second was rejected before
    // any worker execution.
    expect(result.workerCalls).toHaveLength(1);
    expect(result.usage.workerCalls).toBe(1);
  });

  it("stops at maxTokens with TOKEN_BUDGET_EXCEEDED when onBudgetExceeded=FAIL", async () => {
    const a = assignment();
    a.step.orchestration.budget.maxTokens = 20;
    a.step.orchestration.budget.onBudgetExceeded = "FAIL";
    // The orchestrator's first response alone (15) is under the limit;
    // its tool result feeding the second call breaches before the next
    // model invocation.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "final",
        usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
      },
    ]);
    const worker = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 } },
    ]);
    const result = await makeRunner(orch, worker).run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("TOKEN_BUDGET_EXCEEDED");
    expect(result.retryDisposition).toBe("TERMINAL");
  });

  it("returns PAUSED with a BUDGET_REVIEW suspension when onBudgetExceeded=PAUSE_FOR_HUMAN_REVIEW", async () => {
    const a = assignment();
    a.step.orchestration.budget.maxWorkerCalls = 1;
    a.step.orchestration.budget.onBudgetExceeded = "PAUSE_FOR_HUMAN_REVIEW";
    const orch = new ScriptedModel([
      invokeCall("coder"),
      invokeCall("coder"),
    ]);
    const worker = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const result = await makeRunner(orch, worker).run(a, { runId: "run-uuid" });

    expect(result.status).toBe("PAUSED");
    expect(result.retryDisposition).toBe("NONE");
    expect(result.errorCode).toBe("WORKER_BUDGET_EXCEEDED");
    expect(result.suspension).toBeDefined();
    expect(result.suspension!.reason).toBe("BUDGET_REVIEW");
    expect(result.suspension!.continuationId).toBeTruthy();
  });

  it("stops immediately when the rejection budget is exceeded", async () => {
    const a = verifierAssignment();
    a.step.orchestration.budget.maxVerifierRejectionsPerAttempt = 1;
    a.step.orchestration.budget.onBudgetExceeded = "FAIL";
    a.step.orchestration.budget.maxWorkerCalls = 5;
    // Orchestrator: verify (rejected #1 — allowed), verify again
    // (rejected #2 — exceeds). The runner must stop immediately after
    // the second rejection: no further orchestrator turn.
    const orch = new ScriptedModel([
      verifyCall("verifier"),
      verifyCall("verifier"),
      {
        content: "never reached",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    // A fresh rejecting verifier per invocation: every invocation
    // reports REJECTED and finishes.
    const makeRejectingVerifier = () =>
      new ScriptedModel([
        {
          content: "",
          toolCalls: [
            {
              id: "vr",
              name: "report_verdict",
              args: { verdict: "REJECTED", summary: "bad", issues: ["x"] },
            },
          ],
          usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
        },
        { content: "done", usage: { promptTokens: 4, completionTokens: 2, totalTokens: 6 } },
      ]);
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => (info.modelId === "orch-model" ? orch : makeRejectingVerifier()),
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => makeRejectingVerifier() },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("REJECTION_BUDGET_EXCEEDED");
    expect(result.retryDisposition).toBe("TERMINAL");
    // Both verifier invocations recorded; the second rejection
    // terminated the run before the orchestrator's next model call.
    expect(result.verifierResults).toHaveLength(2);
    expect(result.usage.rejectionCount).toBe(2);
  });

  it("returns CANCELLED and never checkpoints after cancellation", async () => {
    // Cancel BEFORE the run: the orchestrator turn returns CANCELLED at
    // its first loop check and no checkpoint is attempted.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "never reached",
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
    ]);
    const worker = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    let checkpointCalls = 0;
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          return worker;
        },
      },
      workerInvoker: new WorkerInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => worker },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      cancellation: { cancelled: true },
      checkpointService: {
        create: async () => {
          checkpointCalls++;
          return { status: "NO_CHANGES" };
        },
      },
      allowCheckpoint: true,
    });
    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("CANCELLED");
    expect(result.retryDisposition).toBe("NONE");
    // §13: cancellation never commits — the checkpoint service was
    // never even called.
    expect(checkpointCalls).toBe(0);
    expect(result.commits).toHaveLength(0);
  });
});