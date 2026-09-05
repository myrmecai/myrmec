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
    expect(result.usage.totalTokens).toBe(15 + 28 + 40);
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
    // Orchestrator loops requesting the worker forever.
    const loop: ModelResponse[] = Array.from({ length: 30 }, () => invokeCall("coder"));
    const orch = new ScriptedModel(loop);
    const worker = new ScriptedModel(
      Array.from({ length: 30 }, () => ({
        content: "done again",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      })),
    );

    const result = await makeRunner(orch, worker).run(assignment(), {
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