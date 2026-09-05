// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * WorkerInvoker contract tests (design §8.5): declaration validation,
 * model resolution, task assembly, and normalized usage enforcement.
 */
import { describe, it, expect } from "vitest";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type { ChatModel, ConversationMessage, ModelResponse, ToolSpec } from "../executor/types.js";
import { WorkerInvoker, normalizeUsage } from "./WorkerInvoker.js";import type { OrchestrationAssignment } from "./types.js";
// InvokeWorkerOutcome is exercised through invoke()'s return value.

// ── helpers ──────────────────────────────────────────────────────────

class ScriptedModel implements ChatModel {
  constructor(private responses: ModelResponse[]) {}
  async invoke(_m: ConversationMessage[], _t: ToolSpec[]): Promise<ModelResponse> {
    const next = this.responses.shift();
    if (!next) throw new Error("scripted model exhausted");
    return next;
  }
}

function baseAssignment(): OrchestrationAssignment {
  return {
    schemaVersion: "1.0",
    dispatch: {
      workflowId: "wf", runId: "run", stepId: "step-1", taskId: "task",
      attemptId: "attempt", attemptOrdinal: 1, dispatchId: "attempt",
    },
    models: [
      { code: "worker-model", provider: "stub", modelId: "worker-model", description: "w", apiEndpoint: null, credentialRef: null, parameters: {} },
    ],
    source: {
      repoUrl: "https://example.com/r.git", sourceBranch: "main",
      sourceBaseCommit: "a".repeat(40), targetBranch: "t", credentialRef: null,
    },
    policy: {
      allowedTools: ["read_file"], commandTemplates: {},
      requiredIsolation: "TRUSTED_PROCESS", approvalPolicy: {},
      gitPolicy: { allowCheckpoint: true, allowPush: false },
      workspaceRetentionSeconds: 3600,
    },
    step: {
      id: "step-1", name: "S", taskType: "ORCHESTRATOR",
      agentProfileCode: "p", dependsOn: [],
      retryPolicy: { maxRetries: 0, initialBackoffSeconds: 1, maxBackoffSeconds: 1 },
      orchestration: {
        modelCode: "worker-model", goal: "G", specPath: null, sourceSubPath: "app",
        workers: [
          { name: "coder", modelCode: "worker-model", capability: "C", allowedTools: ["read_file"], allowedCommands: [] },
        ],
        checkpointStrategy: { mode: "ON_VERIFICATION_PASS", commitMessage: "m", pushToRemote: false, allowNoChanges: false },
        completionCriteria: { definitionOfDone: "D", requireVerificationBy: [] },
        budget: {
          maxTokens: 1000, maxWorkerCalls: 5, maxVerifierRejectionsPerAttempt: 2,
          maxOrchestratorIterations: 10, maxWorkerIterations: 5, onBudgetExceeded: "FAIL",
        },
      },
    },
  };
}

function invokerWith(model: ChatModel): WorkerInvoker {
  return new WorkerInvoker({
    chatModelFactory: { resolve: async () => model },
    turnExecutor: new TurnExecutor({}),
  });
}

// ── normalizeUsage ────────────────────────────────────────────────────

describe("normalizeUsage", () => {
  it("accepts an explicit valid total", () => {
    expect(normalizeUsage({ promptTokens: 3, completionTokens: 4, totalTokens: 7 })).toEqual({
      promptTokens: 3, completionTokens: 4, totalTokens: 7,
    });
  });

  it("computes the total only from both component counts", () => {
    expect(normalizeUsage({ promptTokens: 3, completionTokens: 4 })).toEqual({
      promptTokens: 3, completionTokens: 4, totalTokens: 7,
    });
    // One component alone cannot compute a total.
    expect(normalizeUsage({ promptTokens: 3 })).toBeNull();
  });

  it("rejects missing, negative, and non-integral usage", () => {
    expect(normalizeUsage(undefined)).toBeNull();
    expect(normalizeUsage({})).toBeNull();
    expect(normalizeUsage({ promptTokens: -1, completionTokens: 4 })).toBeNull();
    expect(normalizeUsage({ promptTokens: 1.5, completionTokens: 4 })).toBeNull();
    expect(normalizeUsage({ promptTokens: 3, completionTokens: "4" as unknown as number })).toBeNull();
  });

  it("never estimates: an invalid total falls back to components only", () => {
    expect(normalizeUsage({ promptTokens: 3, completionTokens: 4, totalTokens: -1 })).toBeNull();
  });
});

// ── WorkerInvoker ────────────────────────────────────────────────────

describe("WorkerInvoker", () => {
  const seq = { startedSequence: 1, completedSequence: 1 };
  const rev = { revisionBefore: 0, revisionAfter: 0 };

  it("invokes a declared worker and returns runner-owned identity", async () => {
    const model = new ScriptedModel([
      { content: "did it", usage: { promptTokens: 5, completionTokens: 3, totalTokens: 8 } },
    ]);
    const invoker = invokerWith(model);
    const outcome = await invoker.invoke(
      baseAssignment(), "coder", "IMPLEMENT", "Do work.", seq, rev,
    );
    expect(outcome.workerCall.status).toBe("COMPLETED");
    expect(outcome.summary).toBe("did it");
    expect(outcome.tokenCount).toBe(8);
    expect(outcome.workerCall.callId).toBeTruthy();
    expect(outcome.workerCall.workerName).toBe("coder");
    expect(outcome.workerCall.purpose).toBe("IMPLEMENT");
  });

  it("rejects an undeclared worker before any model execution", async () => {
    let resolveCalls = 0;
    const invoker = new WorkerInvoker({
      chatModelFactory: {
        resolve: async () => {
          resolveCalls++;
          return new ScriptedModel([]);
        },
      },
      turnExecutor: new TurnExecutor({}),
    });
    const outcome = await invoker.invoke(baseAssignment(), "ghost", "IMPLEMENT", "x", seq, rev);
    expect(outcome.workerCall.status).toBe("FAILED");
    expect(outcome.workerCall.errorCode).toBe("ASSIGNMENT_VALIDATION_ERROR");
    expect(resolveCalls).toBe(0);
  });

  it("rejects a worker model code absent from assignment.models", async () => {
    const a = baseAssignment();
    a.models = [];
    const invoker = invokerWith(new ScriptedModel([]));
    const outcome = await invoker.invoke(a, "coder", "IMPLEMENT", "x", seq, rev);
    expect(outcome.workerCall.status).toBe("FAILED");
    expect(outcome.workerCall.errorCode).toBe("ASSIGNMENT_VALIDATION_ERROR");
  });

  it("fails with TOKEN_USAGE_UNAVAILABLE for a response without usage", async () => {
    const model = new ScriptedModel([{ content: "done" }]);
    const invoker = invokerWith(model);
    const outcome = await invoker.invoke(baseAssignment(), "coder", "IMPLEMENT", "x", seq, rev);
    expect(outcome.workerCall.status).toBe("FAILED");
    expect(outcome.workerCall.errorCode).toBe("TOKEN_USAGE_UNAVAILABLE");
  });

  it("classifies a worker iteration cap as WORKER_ITERATION_LIMIT", async () => {
    // The worker loops: requests the same (undeclared) tool forever, which
    // is recorded and fed back — hitting maxWorkerIterations.
    const looping: ModelResponse[] = Array.from({ length: 10 }, (_, i) => ({
      content: "",
      toolCalls: [{ id: `c-${i}`, name: "loop_tool", args: {} }],
      usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
    }));
    const invoker = invokerWith(new ScriptedModel(looping));
    const outcome = await invoker.invoke(baseAssignment(), "coder", "IMPLEMENT", "x", seq, rev);
    expect(outcome.workerCall.status).toBe("FAILED");
    expect(outcome.workerCall.errorCode).toBe("WORKER_ITERATION_LIMIT");
  });

  it("classifies a worker provider error as WORKER_FAILED", async () => {
    class Boom implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("worker provider down");
      }
    }
    const invoker = invokerWith(new Boom());
    const outcome = await invoker.invoke(baseAssignment(), "coder", "IMPLEMENT", "x", seq, rev);
    expect(outcome.workerCall.status).toBe("FAILED");
    expect(outcome.workerCall.errorCode).toBe("WORKER_FAILED");
  });
});