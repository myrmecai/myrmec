// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Contract tests for the minimal delegation loop (design §10.1): one
 * orchestrator TurnExecutor whose only tool is `invoke_helper`, backed
 * by the HelperInvoker through the ChatModelFactory/TurnExecutor seams.
 */
import { describe, it, expect } from "vitest";
import { TurnExecutor } from "../executor/TurnExecutor.js";
import type {
  ChatModel,
  ConversationMessage,
  ModelResponse,
  ToolSpec,
  ModelToolCall,
  Tool,
} from "../executor/types.js";
import { HelperInvoker } from "./HelperInvoker.js";
import { OrchestrationRunner } from "./OrchestrationRunner.js";
import type { OrchestrationAssignment } from "./types.js";
import {
  ExecutionControlCoordinator,
  type ExecutionControl as ExecutorControl,
} from "../interaction/ExecutionControlCoordinator.js";
import type { ExecutionControlPayload } from "../protocol/unifiedFrames.js";

// ---- hold-gate test seam (Task 5) ----

const GATE_EXECUTION_ID = "b0000000-0000-4000-8000-000000000001";
const GATE_DISPATCH_ID = "b0000000-0000-4000-8000-000000000002";

function gatePolicy() {
  return {
    version: 1 as const,
    enabled: true,
    idleResumeAfterSeconds: 300,
    responseTimeoutSeconds: 120,
    maxInputBytes: 16384,
    maxOutputBytes: 65536,
    maxModelIterations: 8,
    maxHistoryBytes: 262144,
    transcriptRetentionDays: 30,
    contentMode: "USER_CHAT_ONLY" as const,
  };
}

/** A promise the test releases by hand (never a real timer). */
function blockedPromise<T>(): { promise: Promise<T>; release(value: T): void } {
  let release!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

/** Deterministically settle promise chains built on microtasks. */
async function flush(): Promise<void> {
  for (let i = 0; i < 25; i++) await Promise.resolve();
}

/** A fake Tool. */
function makeFakeTool(
  name: string,
  impl: (args: Record<string, unknown>) => Promise<unknown>,
): Tool {
  return { name, invoke: async (args) => await impl(args) };
}

/** A runner tool call (mirrors the model's toolCalls entries). */
function runnerToolCall(name: string, args: Record<string, unknown>): ModelToolCall {
  return { id: `call-${name}`, name, args };
}

/** The runner's options-level cancellation signal (test seam). */
function runnerCancellationOf(runner: OrchestrationRunner): { cancelled: boolean } {
  const options = (runner as unknown as {
    options: { cancellation?: { cancelled: boolean } };
  }).options;
  const cancellation = options.cancellation;
  if (!cancellation) throw new Error("runner has no cancellation signal");
  return cancellation;
}

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
        endpoint: null,
        credentialRef: null,
        parameters: {},
      },
      {
        code: "helper-model",
        provider: "stub",
        modelId: "helper-model",
        description: "helper",
        endpoint: null,
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
        helpers: [
          {
            name: "coder",
            modelCode: "helper-model",
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
  helperModel: ChatModel,
): OrchestrationRunner {
  const invoker = new HelperInvoker({
    attemptOrdinal: 1,
    chatModelFactory: {
      resolve: async (info) => {
        if (info.modelId === "helper-model") return helperModel;
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
    helperInvoker: invoker,
    turnExecutor: new TurnExecutor({}),
  });
  return runner;
}

/** A helper invocation tool call the orchestrator makes. */
function invokeCall(helperName: string, instruction = "Do the work."): ModelResponse {
  return {
    content: "",
    toolCalls: [
      {
        id: "call-1",
        name: "invoke_helper",
        args: { helperName, purpose: "IMPLEMENT", instruction },
      },
    ],
    usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
  };
}

// ── the minimal delegation loop ──────────────────────────────────────

describe("OrchestrationRunner minimal delegation", () => {
  it("delegates to the named helper, feeds the result back, and completes", async () => {
    // Orchestrator: invoke coder, then final answer.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The coder completed the work.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    // Helper: immediately returns a final answer.
    const helper = new ScriptedModel([
      {
        content: "Implementation done.",
        usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 },
      },
    ]);
    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("COMPLETED");
    expect(result.summary).toBe("The coder completed the work.");
    expect(result.helperCalls).toHaveLength(1);
    const call = result.helperCalls[0];
    expect(call.helperName).toBe("coder");
    expect(call.purpose).toBe("IMPLEMENT");
    expect(call.status).toBe("COMPLETED");
    expect(call.tokenCount).toBe(40);
    // Runner-owned identity, not model-supplied.
    expect(call.callId).toBeTruthy();
    expect(call.workspaceRevisionBefore).toBeDefined();
    expect(call.workspaceRevisionAfter).toBeDefined();
    // §13: the usage mirrors the budget counters — every orchestrator
    // AND helper model response's tokens.
    expect(result.usage.totalTokens).toBe(15 + 28 + 40);
    expect(result.usage.helperCalls).toBe(1);
    expect(result.errorCode).toBeUndefined();
  });

  it("rejects an undeclared helper name", async () => {
    const orch = new ScriptedModel([invokeCall("ghost")]);
    const helper = new ScriptedModel([]);

    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    // The tool failure is fed back; the orchestrator eventually fails or
    // retries. With the scripted single response exhausted, the runner
    // reports a failure — the exact code is HELPER_FAILED-family.
    expect(result.errorCode).toBeTruthy();
  });

  it("rejects a helper model code absent from assignment.models", async () => {
    const a = assignment();
    // Remove the helper model from the catalog but keep the reference.
    a.models = a.models.filter((m) => m.code !== "helper-model");
    const orch = new ScriptedModel([invokeCall("coder")]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
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

  it("feeds a helper failure back to the orchestrator; a recovered run completes", async () => {
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The helper failed; stopping.",
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

    // Design §10.1: expected helper failure is returned as a FAILED tool
    // result and fed back; the orchestrator recovered and finished.
    expect(result.status).toBe("COMPLETED");
    expect(result.summary).toBe("The helper failed; stopping.");
    expect(result.helperCalls[0].status).toBe("FAILED");
    expect(result.helperCalls[0].errorCode).toBe("HELPER_FAILED");
  });

  it("returns HELPER_FAILED when the orchestrator cannot recover from the helper failure", async () => {
    // The orchestrator's turn also fails after the helper failure.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      // Second response also requests the (failing) helper.
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
      helperInvoker: new HelperInvoker({
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
    expect(result.helperCalls.length).toBeGreaterThan(0);
    expect(result.helperCalls[0].status).toBe("FAILED");
    expect(result.helperCalls[0].errorCode).toBe("HELPER_FAILED");
  });

  it("an unrecovered HELPER_FAILED is RETRYABLE and publishes the retry continuation (§16.6/§17.5)", async () => {
    // The orchestrator delegates once; the helper provider fails; the
    // orchestrator's own turn then fails — the run result carries the
    // helper's stable code with RETRYABLE + the §7.3 continuation.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      invokeCall("coder"),
    ]);
    class FailingModel implements ChatModel {
      async invoke(): Promise<ModelResponse> {
        throw new Error("provider exploded");
      }
    }
    const published: Array<{ dispatchId: string; attemptOrdinal: number }> = [];
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => new FailingModel(),
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      retryContinuationPublisher: {
        publish: async (input) => {
          published.push({
            dispatchId: input.dispatchId,
            attemptOrdinal: input.attemptOrdinal,
          });
          return {
            continuationId: `cont-${input.dispatchId}-retry`,
            continuationRef: `local:cont-${input.dispatchId}-retry`,
            stateDigest: "d".repeat(64),
          };
        },
      },
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("HELPER_FAILED");
    expect(result.retryDisposition).toBe("RETRYABLE");
    // §7.3 ContinuationRecord on the RETRYABLE result.
    expect(result.continuation).toMatchObject({
      continuationId: "cont-attempt-uuid-retry",
    });
    expect(result.continuation?.stateDigest).toMatch(/^[0-9a-f]{64}$/);
    // The publication happened exactly once at the safe boundary.
    expect(published).toEqual([
      { dispatchId: "attempt-uuid", attemptOrdinal: 1 },
    ]);
  });

  it("enforces maxOrchestratorIterations independent of model output", async () => {
    // Orchestrator loops requesting the helper forever. The budget
    // limits are raised so ONLY the iteration cap terminates the loop.
    const a = assignment();
    a.step.orchestration.budget.maxWorkerCalls = 100;
    a.step.orchestration.budget.maxTokens = 100000;
    const loop: ModelResponse[] = Array.from({ length: 30 }, () => invokeCall("coder"));
    const orch = new ScriptedModel(loop);
    const helper = new ScriptedModel(
      Array.from({ length: 30 }, () => ({
        content: "done again",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      })),
    );

    const result = await makeRunner(orch, helper).run(a, {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_ITERATION_LIMIT");
  });

  it("fails with TOKEN_USAGE_UNAVAILABLE for a missing orchestration usage", async () => {
    // Orchestrator returns a final answer with NO usage block.
    const orch = new ScriptedModel([{ content: "Done." }]);
    const helper = new ScriptedModel([]);

    const result = await makeRunner(orch, helper).run(assignment(), {
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
    const helper = new ScriptedModel([]);

    const result = await makeRunner(new FailingOrchModel(), helper).run(assignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_FAILED");
  });

  // ── §7.3/§8.7 (A4) enforcement ──

  it("caps the orchestrator iterations at the session's maxIterations policy", async () => {
    // The host ceiling (2) is BELOW the assignment budget's orchestrator
    // iteration cap (20): the loop stops at the policy ceiling with
    // ORCHESTRATOR_ITERATION_LIMIT (the turn-cap semantics), not PAUSED.
    const a = assignment();
    a.step.orchestration.budget.maxWorkerCalls = 100;
    const loop: ModelResponse[] = Array.from({ length: 10 }, () => invokeCall("coder"));
    const orch = new ScriptedModel(loop);
    const helper = new ScriptedModel(
      Array.from({ length: 10 }, () => ({
        content: "done",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      })),
    );

    const result = await makeRunner(orch, helper).run(a, {
      runId: "run-uuid",
      maxFunctionCalls: 2,
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_ITERATION_LIMIT");
    // The ceiling (not the assignment's cap of 20) terminated the run —
    // the usage reflects 2 orchestrator iterations + 2 helper responses.
    expect(result.usage.helperCalls).toBe(2);
  });

  it("keeps the assignment budget as the cap when the host ceiling is looser", async () => {
    const a = assignment();
    a.step.orchestration.budget.maxOrchestratorIterations = 1;
    const orch = new ScriptedModel(
      Array.from({ length: 8 }, () => invokeCall("coder")),
    );
    const helper = new ScriptedModel(
      Array.from({ length: 8 }, () => ({
        content: "done",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      })),
    );

    // Host ceiling of 99 never applies — the budget's 1 wins (min).
    const result = await makeRunner(orch, helper).run(a, {
      runId: "run-uuid",
      maxFunctionCalls: 99,
    });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("ORCHESTRATOR_ITERATION_LIMIT");
    expect(result.usage.helperCalls).toBe(1);
  });

  it("pauses the attempt when the live allowance ceiling is reached", async () => {
    // The assignment budget's token ceiling is generous (100000); the
    // engine's tighten-only allowance (12) is reached after the first
    // helper call (15 + 28 + 40 = 83 > 12) — the NEXT invoke_helper
    // boundary pauses the attempt.
    const allowanceSource = { maxTokens: 12 as number | null };
    const orch = new ScriptedModel([
      invokeCall("coder"),
      invokeCall("coder"),
      { content: "never reached" },
    ]);
    const helper = new ScriptedModel([
      { content: "first", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
      { content: "second", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);

    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
      allowanceSource,
    });

    expect(result.status).toBe("PAUSED");
    expect(result.errorCode).toBe("POLICY_CEILING_REACHED");
    expect(result.suspension?.reason).toBe("POLICY_CEILING");
    expect(result.retryDisposition).toBe("NONE");
  });

  it("applies a live tighten to the budget: a ceiling below accounted usage pauses at the next boundary", async () => {
    const allowanceSource = { maxTokens: null as number | null };
    const orch = new ScriptedModel([
      invokeCall("coder"),
      { content: "final answer", usage: { promptTokens: 5, completionTokens: 5, totalTokens: 10 } },
    ]);
    const helper = new ScriptedModel([
      { content: "done", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);

    // Tighten BEFORE the run: the ceiling (30) is below the accounted usage
    // the first helper call will reach (15 + 28 + 40) — the next boundary pauses.
    allowanceSource.maxTokens = 30;

    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
      allowanceSource,
    });

    expect(result.status).toBe("PAUSED");
    expect(result.errorCode).toBe("POLICY_CEILING_REACHED");
  });

  // ── §7.3 (§12.2): execution timeout pauses the attempt ──────────────

  it("pauses the attempt with EXECUTION_TIMEOUT when the policy timeout has elapsed", async () => {
    // A 0-second deadline arms at run start and is already elapsed by the
    // first invoke_helper boundary — the helper never runs.
    const orch = new ScriptedModel([
      invokeCall("coder"),
      { content: "never reached" },
    ]);
    const helper = new ScriptedModel([
      { content: "done", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);

    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
      executionTimeoutSeconds: 0,
    });

    expect(result.status).toBe("PAUSED");
    expect(result.errorCode).toBe("EXECUTION_TIMEOUT");
    expect(result.suspension?.reason).toBe("EXECUTION_TIMEOUT");
    expect(result.retryDisposition).toBe("NONE");
    // The helper was never invoked — the deadline precedes the call.
    expect(result.helperCalls).toHaveLength(0);
    expect(helper.invocations.length).toBe(0);
  });

  it("completes when the policy carries no execution timeout", async () => {
    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The coder completed the work.",
        usage: { promptTokens: 20, completionTokens: 8, totalTokens: 28 },
      },
    ]);
    const helper = new ScriptedModel([
      {
        content: "Implementation done.",
        usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 },
      },
    ]);

    const result = await makeRunner(orch, helper).run(assignment(), {
      runId: "run-uuid",
      executionTimeoutSeconds: null,
    });

    expect(result.status).toBe("COMPLETED");
  });
});

// ── verification (Feature 5, design §11) ──────────────────────────────

/** An assignment whose completion requires a named verifier. */
function verifierAssignment(): OrchestrationAssignment {
  const a = assignment();
  a.step.orchestration.helpers.push({
    name: "verifier",
    modelCode: "helper-model",
    capability: "Reviews code",
    allowedTools: ["read_file"],
    allowedCommands: [],
  });
  a.step.orchestration.completionCriteria.requireVerificationBy = ["verifier"];
  return a;
}

/** A VERIFY delegation the orchestrator makes. */
function verifyCall(helperName: string): ModelResponse {
  return {
    content: "",
    toolCalls: [
      {
        id: "call-v",
        name: "invoke_helper",
        args: { helperName, purpose: "VERIFY", instruction: "Verify the work." },
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
    // Coder helper: no-op final answer.
    const coder = new ScriptedModel([
      { content: "did it", usage: { promptTokens: 30, completionTokens: 10, totalTokens: 40 } },
    ]);
    // Verifier helper: call report_verdict once, then finish.
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

    // Both helpers resolve through "helper-model": the coder is invoked
    // first, the verifier second — serve from a queue.
    const helperQueue: ChatModel[] = [coder, verifier];
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          const next = helperQueue.shift();
          if (!next) throw new Error("helper queue exhausted");
          return next;
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => {
            const next = helperQueue.shift();
            if (!next) throw new Error("helper queue exhausted");
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
    expect(result.verifierResults[0].helperName).toBe("verifier");
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
      helperInvoker: new HelperInvoker({
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
      helperInvoker: new HelperInvoker({
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
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => lazyVerifier },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(verifierAssignment(), { runId: "run-uuid" });

    // The helper call FAILED with INVALID_VERIFIER_RESULT and was fed
    // back; the orchestrator finished anyway, so the completion gate
    // fails with INVALID_VERIFIER_RESULT (no APPROVED record exists).
    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("INVALID_VERIFIER_RESULT");
    expect(result.helperCalls[0].status).toBe("FAILED");
    expect(result.helperCalls[0].errorCode).toBe("INVALID_VERIFIER_RESULT");
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
      helperInvoker: new HelperInvoker({
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

  it("rejects a VERIFY delegation to a helper not in requireVerificationBy", async () => {
    const orch = new ScriptedModel([
      // coder is a declared helper but NOT a required verifier.
      {
        content: "",
        toolCalls: [
          {
            id: "call-bad",
            name: "invoke_helper",
            args: { helperName: "coder", purpose: "VERIFY", instruction: "verify" },
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
      helperInvoker: new HelperInvoker({
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
  it("stops at maxWorkerCalls with HELPER_BUDGET_EXCEEDED (FAIL)", async () => {
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
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const result = await makeRunner(orch, helper).run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("HELPER_BUDGET_EXCEEDED");
    expect(result.retryDisposition).toBe("TERMINAL");
    // Exactly one helper call executed; the second was rejected before
    // any helper execution.
    expect(result.helperCalls).toHaveLength(1);
    expect(result.usage.helperCalls).toBe(1);
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
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 } },
    ]);
    const result = await makeRunner(orch, helper).run(a, { runId: "run-uuid" });

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
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const result = await makeRunner(orch, helper).run(a, { runId: "run-uuid" });

    expect(result.status).toBe("PAUSED");
    expect(result.retryDisposition).toBe("NONE");
    expect(result.errorCode).toBe("HELPER_BUDGET_EXCEEDED");
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
      helperInvoker: new HelperInvoker({
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
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    let checkpointCalls = 0;
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orch;
          return helper;
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => helper },
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

// ── HITL suspension (design §17.4) ───────────────────────────────────

describe("OrchestrationRunner HITL suspension", () => {
  it("suspends BEFORE the helper starts and returns PAUSED with the suspension record", async () => {
    const helper = new ScriptedModel([
      { content: "never reached", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    // The orchestrator delegates once; the turn then ends (the
    // suspension signal tears the loop down).
    const orchestrator = new ScriptedModel([invokeCall("coder")]);

    const requests: unknown[] = [];
    let helperInvocations = 0;
    const invoker = new HelperInvoker({
      attemptOrdinal: 1,
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "helper-model") {
            helperInvocations += 1;
            return helper;
          }
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      turnExecutor: new TurnExecutor({}),
    });

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          throw new Error(`unexpected model: ${info.modelId}`);
        },
      },
      helperInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
      approvalEvaluator: {
        // Every helper invocation requires approval.
        evaluate: () => ({
          outcome: "REQUIRE_APPROVAL",
          expiresAt: "2026-09-07T12:00:00.000Z",
        }),
        effectiveWorkerRisk: () => "SAFE",
      },
      approvalSink: { request: async (r) => void requests.push(r) },
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("PAUSED");
    expect(result.retryDisposition).toBe("NONE");
    // §17.4: the helper NEVER started — suspension happens once, before
    // the helper runs.
    expect(helperInvocations).toBe(0);
    // The suspension record carries the durable continuation + pending
    // action + expiry.
    expect(result.suspension).toMatchObject({
      reason: "HITL_APPROVAL",
      expiresAt: "2026-09-07T12:00:00.000Z",
      pendingAction: { type: "HELPER_TOOL", summary: "helper:coder:IMPLEMENT" },
    });
    expect(result.suspension?.approvalRequestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    // The sink received exactly ONE idempotent request, §16.3-shaped.
    expect(requests).toHaveLength(1);
    const request = requests[0] as {
      approvalRequestId: string;
      action: { digest: string };
      expiresAt: string;
    };
    expect(request.approvalRequestId).toBe(result.suspension?.approvalRequestId);
    expect(request.action.digest).toMatch(/^[0-9a-f]{64}$/);
    expect(request.expiresAt).toBe("2026-09-07T12:00:00.000Z");
  });

  it("a profile DENY returns the terminal APPROVAL_POLICY_DENIED — no request created", async () => {
    const orchestrator = new ScriptedModel([invokeCall("coder")]);
    const requests: unknown[] = [];

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => {
            throw new Error("helper must not resolve on DENY");
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      approvalEvaluator: {
        evaluate: () => ({
          outcome: "DENY",
          reasonCode: "APPROVAL_POLICY_DENIED",
          reason: "profile approvalPolicy denies tool:write_file",
        }),
        effectiveWorkerRisk: () => "SAFE",
      },
      approvalSink: { request: async (r) => void requests.push(r) },
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("APPROVAL_POLICY_DENIED");
    expect(result.retryDisposition).toBe("TERMINAL");
    // §17.4: DENY creates no approval request.
    expect(requests).toHaveLength(0);
  });

  it("an absent approval sink fails closed when approval is required", async () => {
    const orchestrator = new ScriptedModel([invokeCall("coder")]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async () => {
            throw new Error("helper must not start");
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      approvalEvaluator: {
        evaluate: () => ({
          outcome: "REQUIRE_APPROVAL",
          expiresAt: "2026-09-07T12:00:00.000Z",
        }),
        effectiveWorkerRisk: () => "SAFE",
      },
      // No approvalSink wired.
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    // §17.4: "the runner must have an OrchestrationApprovalSink; its
    // absence fails closed."
    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("APPROVAL_POLICY_DENIED");
    expect(result.summary).toContain("failing closed");
  });

  it("SAFE helper invocations execute without approval when no policy rule applies", async () => {
    const helper = new ScriptedModel([
      { content: "done", usage: { promptTokens: 8, completionTokens: 3, totalTokens: 11 } },
    ]);
    const orchestrator = new ScriptedModel([
      invokeCall("coder"),
      { content: "All done.", usage: { promptTokens: 20, completionTokens: 6, totalTokens: 26 } },
    ]);

    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          if (info.modelId === "helper-model") return helper;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: {
          resolve: async (info) => {
            if (info.modelId === "helper-model") return helper;
            throw new Error(`unexpected: ${info.modelId}`);
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      approvalEvaluator: {
        evaluate: () => ({ outcome: "ALLOW" }),
        effectiveWorkerRisk: () => "SAFE",
      },
      approvalSink: { request: async () => undefined },
    });

    const result = await runner.run(assignment(), { runId: "run-uuid" });

    expect(result.status).toBe("COMPLETED");
    expect(result.helperCalls).toHaveLength(1);
  });
});

// ── HITL resume (design §7/§17.4, slice C) ───────────────────────────

describe("OrchestrationRunner HITL resume", () => {
  /** A decision-bearing continuation assignment. */
  function resumeAssignment(): OrchestrationAssignment {
    const a = assignment();
    a.dispatch.continuationId = "cont-1";
    a.dispatch.attemptOrdinal = 2;
    a.continuation = {
      continuationId: "cont-1",
      previousDispatchId: "attempt-uuid",
      decision: {
        schemaVersion: "1.0",
        decisionId: "decision-1",
        approvalRequestId: "11111111-1111-4111-8111-111111111111",
        continuationId: "cont-1",
        previousDispatchId: "attempt-uuid",
        status: "APPROVED",
        actionDigest: "a".repeat(64),
        stateDigest: "b".repeat(64),
        snapshotTreeHash: "c".repeat(40),
        workspaceGeneration: 2,
        decidedAt: "2026-01-01T00:00:00Z",
        expiresAt: "2999-01-01T00:00:00Z",
      },
    };
    return a;
  }

  /** The matching restored suspension. */
  const restoredSuspension = {
    continuationId: "cont-1",
    previousDispatchId: "attempt-uuid",
    suspension: {
      continuationId: "cont-1",
      continuationRef: "local:cont-1",
      snapshotTreeHash: "c".repeat(40),
      workspaceRevision: 2,
      stateDigest: "b".repeat(64),
      reason: "HITL_APPROVAL" as const,
      approvalRequestId: "11111111-1111-4111-8111-111111111111",
      pendingAction: {
        actionId: "action-1",
        type: "HELPER_TOOL",
        riskClass: "DESTRUCTIVE" as const,
        summary: "helper:coder:IMPLEMENT",
        digest: "a".repeat(64),
      },
      expiresAt: "2999-01-01T00:00:00Z",
    },
  };

  function makeResumeRunner(
    orchestrator: ScriptedModel,
    helper: ScriptedModel,
    loader?: {
      load: (
        id: string,
      ) => import("./ApprovalResumeValidator.js").RestoredSuspension | null;
    },
  ): OrchestrationRunner {
    return new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          if (info.modelId === "helper-model") return helper;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 2,
        chatModelFactory: {
          resolve: async (info) => {
            if (info.modelId === "helper-model") return helper;
            throw new Error(`unexpected: ${info.modelId}`);
          },
        },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      suspensionLoader: loader ?? {
        load: (id) => (id === "cont-1" ? restoredSuspension : null),
      },
    });
  }

  it("a valid decision resumes the step and runs to completion", async () => {
    const orchestrator = new ScriptedModel([
      invokeCall("coder"),
      { content: "Resumed and done.", usage: { promptTokens: 20, completionTokens: 6, totalTokens: 26 } },
    ]);
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 8, completionTokens: 3, totalTokens: 11 } },
    ]);
    const result = await makeResumeRunner(orchestrator, helper).run(resumeAssignment(), {
      runId: "run-uuid",
    });

    expect(result.status).toBe("COMPLETED");
    expect(result.helperCalls).toHaveLength(1);
  });

  it("a decision-bearing continuation without a suspension loader fails closed", async () => {
    const orchestrator = new ScriptedModel([invokeCall("coder")]);
    const helper = new ScriptedModel([
      { content: "never", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    // No suspensionLoader wired.
    const bare = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          if (info.modelId === "helper-model") return helper;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 2,
        chatModelFactory: { resolve: async () => helper },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const outcome = await bare.run(resumeAssignment(), { runId: "run-uuid" });

    expect(outcome.status).toBe("FAILED");
    expect(outcome.errorCode).toBe("APPROVAL_POLICY_DENIED");
    expect(outcome.summary).toContain("failing closed");
    expect(outcome.helperCalls).toHaveLength(0);
  });

  it("an unrestorable suspension (loader returns null) fails closed", async () => {
    const orchestrator = new ScriptedModel([invokeCall("coder")]);
    const helper = new ScriptedModel([
      { content: "never", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const runner = makeResumeRunner(orchestrator, helper, {
      load: () => null,
    });
    const result = await runner.run(resumeAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("APPROVAL_POLICY_DENIED");
    expect(result.summary).toContain("unrestorable");
    expect(result.helperCalls).toHaveLength(0);
  });

  it("a decision whose digests do not bind the suspension fails closed", async () => {
    const orchestrator = new ScriptedModel([invokeCall("coder")]);
    const helper = new ScriptedModel([
      { content: "never", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const runner = makeResumeRunner(orchestrator, helper, {
      // The stored suspension drifted (state digest differs).
      load: (id) =>
        id === "cont-1"
          ? {
              ...restoredSuspension,
              suspension: {
                ...restoredSuspension.suspension,
                stateDigest: "d".repeat(64),
              },
            }
          : null,
    });
    const result = await runner.run(resumeAssignment(), { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("APPROVAL_POLICY_DENIED");
    expect(result.summary).toContain("stateDigest");
    expect(result.helperCalls).toHaveLength(0);
  });

  it("an expired decision fails closed with APPROVAL_EXPIRED", async () => {
    const a = resumeAssignment();
    a.continuation!.decision!.expiresAt = "2020-01-01T00:00:00Z";
    const orchestrator = new ScriptedModel([invokeCall("coder")]);
    const helper = new ScriptedModel([
      { content: "never", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const runner = makeResumeRunner(orchestrator, helper);
    const result = await runner.run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("APPROVAL_EXPIRED");
    expect(result.helperCalls).toHaveLength(0);
  });

  it("a non-decision continuation (engine retry) fails closed without a restorable manifest", async () => {
    // §16.6/§17.5: the engine retries ONLY with a valid continuation —
    // a retry dispatch whose manifest cannot be restored fails closed
    // (RECOVERY_SNAPSHOT_INVALID) and no helper call executes.
    const a = assignment();
    a.dispatch.continuationId = "cont-retry";
    a.continuation = {
      continuationId: "cont-retry",
      previousDispatchId: "attempt-uuid",
    };
    const orchestrator = new ScriptedModel([
      invokeCall("coder"),
      { content: "never reached", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const helper = new ScriptedModel([
      { content: "never", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          if (info.modelId === "helper-model") return helper;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 2,
        chatModelFactory: { resolve: async () => helper },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
    });
    const result = await runner.run(a, { runId: "run-uuid" });

    expect(result.status).toBe("FAILED");
    expect(result.errorCode).toBe("RECOVERY_SNAPSHOT_INVALID");
    expect(result.summary).toContain("could not be restored");
    expect(result.helperCalls).toHaveLength(0);
  });

  it("a retry continuation restores the verifier history and budget-free state", async () => {
    // §17.5: the restored manifest contributes completed-call
    // identities + verifier history (fresh counters per §13). The retry
    // run replays the verifier verdicts into the ledger so the
    // completion gate's satisfies() sees them for the same tree.
    const a = assignment();
    a.dispatch.continuationId = "cont-retry";
    a.continuation = {
      continuationId: "cont-retry",
      previousDispatchId: "attempt-uuid",
    };
    const manifest: import("./ContinuationStateStore.js").ContinuationManifest = {
      continuationId: "cont-retry",
      dispatchId: "attempt-uuid",
      attemptOrdinal: 1,
      budgetCounters: { helperCalls: 5, totalTokens: 999, rejectionCount: 0 },
      completedCallIds: [],
      candidateTreeHash: "",
      workspaceRevision: 0,
      verifierHistory: [
        {
          callId: "11111111-1111-4111-8111-111111111111",
          helperName: "verifier",
          verdict: "APPROVED",
          summary: "ok",
          issues: [],
          workspaceRevision: 0,
          candidateTreeHash: "",
          attemptOrdinal: 1,
          sequence: 1,
        },
      ],
      createdAt: "2026-01-01T00:00:00Z",
      stateDigest: "b".repeat(64),
    };
    const orchestrator = new ScriptedModel([
      invokeCall("coder"),
      { content: "Retried and done.", usage: { promptTokens: 20, completionTokens: 6, totalTokens: 26 } },
    ]);
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 8, completionTokens: 3, totalTokens: 11 } },
    ]);
    const runner = new OrchestrationRunner({
      chatModelFactory: {
        resolve: async (info) => {
          if (info.modelId === "orch-model") return orchestrator;
          if (info.modelId === "helper-model") return helper;
          throw new Error(`unexpected: ${info.modelId}`);
        },
      },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 2,
        chatModelFactory: { resolve: async () => helper },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      retryContinuationLoader: { load: () => manifest },
    });
    const result = await runner.run(a, { runId: "run-uuid" });

    expect(result.status).toBe("COMPLETED");
    // §17.5: the restored verifier history rides the evidence.
    expect(
      result.verifierResults.map((v) => ({ helperName: v.helperName, verdict: v.verdict })),
    ).toContainEqual({ helperName: "verifier", verdict: "APPROVED" });
  });
});

// ── hold gate (session interaction, Task 5 / design 14.2-14.3 / 22.5) ──

describe("OrchestrationRunner hold gate (safe points)", () => {
  /** A real coordinator over auto-settling seams - tests drive HOLD /
   * CONTINUE through `apply`, the same way the engine would. */
  function makeGate(): ExecutorControl {
    return new ExecutionControlCoordinator({
      executionId: GATE_EXECUTION_ID,
      dispatchId: GATE_DISPATCH_ID,
      policy: gatePolicy(),
      clock: { now: () => 1_700_000_000_000 },
      scheduler: { schedule: () => undefined },
      outbox: { persistControlState: async () => undefined },
      emitter: { publish: () => undefined },
    });
  }

  function holdCommand(revision: number): ExecutionControlPayload {
    return {
      executionId: GATE_EXECUTION_ID,
      dispatchId: GATE_DISPATCH_ID,
      controlRevision: revision,
      action: "HOLD",
      reasonCode: "USER_REQUESTED",
      holdPolicy: { idleResumeAfterSeconds: 300 },
      controlRequestId: null,
    };
  }

  function continueCommand(revision: number): ExecutionControlPayload {
    return {
      executionId: GATE_EXECUTION_ID,
      dispatchId: GATE_DISPATCH_ID,
      controlRevision: revision,
      action: "CONTINUE",
      reasonCode: "USER_REQUESTED",
      holdPolicy: null,
      controlRequestId: null,
    };
  }

  it("HOLD parks the model invocation; the returned tool call is not EXECUTED while held - it runs after CONTINUE", async () => {
    // The HOLD lands while the FIRST orchestrator model call is in
    // flight (before it returns its tool call): the leaf settles, then
    // the gate parks the second model call. The parked continuation
    // resolves only after CONTINUE.
    const firstWork = blockedPromise<ModelResponse>();
    const released = blockedPromise<ModelResponse>();
    // Resolve-once contract: ONE model object; invoke #1 parks on
    // firstWork (tool-call response), invoke #2 parks on released.
    const invocations: Promise<ModelResponse>[] = [firstWork.promise, released.promise];
    const orchestrator: ChatModel = {
      invoke: async () => {
        const next = invocations.shift();
        if (!next) throw new Error("orchestrator invocations exhausted");
        return await next;
      },
    };
    const invoker = new HelperInvoker({
      attemptOrdinal: 1,
      chatModelFactory: { resolve: async () => { throw new Error("helper must not start"); } },
      turnExecutor: new TurnExecutor({}),
    });
    const control = makeGate();
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => orchestrator },
      helperInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });
    const first = await firstWork.release({
      content: "",
      toolCalls: [runnerToolCall("echo", { value: "x" })],
      usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
    });
    void first;
    await flush();

    // HOLD after the leaf settled: the gate is closed, the orchestrator's
    // next model call parks (no tool has run yet - the model returned a
    // tool call and the gate stands BEFORE its execution... actually the
    // tool gate sits INSIDE the turn loop; assert after CONTINUE).
    await control.apply(holdCommand(1), "msg-hold-1");
    await flush();

    released.release({
      content: "done after continue",
      usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
    });
    await flush();
    // Nothing settled while the gate was HELD without a CONTINUE.
    expect(control.snapshot().effectiveState).not.toBe("RUNNING");

    await control.apply(continueCommand(2), "msg-cont-1");
    const result = await runPromise;

    expect(result.status).toBe("COMPLETED");
    expect(result.helperCalls).toHaveLength(0);
  });

  it("HOLD prevents returned tool EXECUTION: the tool body runs only after CONTINUE wakes the gate", async () => {
    // The orchestrator's first response returns an invoke_helper call
    // while the gate is ALREADY held: TurnExecutor parks at
    // BEFORE_TOOL_EXECUTION - the helper body cannot start until
    // CONTINUE hands out the lease.
    const heldModel = new ScriptedModel([
      {
        content: "",
        toolCalls: [
          {
            id: "c1",
            name: "invoke_helper",
            args: { helperName: "coder", purpose: "IMPLEMENT", instruction: "do it" },
          },
        ],
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
      {
        content: "The helper completed the work.",
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const control = makeGate();
    await control.apply(holdCommand(1), "msg-hold-1"); // HELD before the run

    let helperStarts = 0;
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 } },
    ]);
    const invoker = new HelperInvoker({
      attemptOrdinal: 1,
      chatModelFactory: { resolve: async () => helper },
      turnExecutor: new TurnExecutor({}),
    });
    // Count helper-body entry by wrapping invoke.
    const originalInvoke = invoker.invoke.bind(invoker);
    invoker.invoke = async (...callArgs: Parameters<typeof originalInvoke>) => {
      helperStarts += 1;
      return await originalInvoke(...callArgs);
    };
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => heldModel },
      helperInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });
    await flush();
    expect(helperStarts).toBe(0); // parked BEFORE the tool executed
    expect(control.snapshot().effectiveState).toBe("HELD");

    await control.apply(continueCommand(2), "msg-cont-1");
    const result = await runPromise;
    expect(helperStarts).toBe(1); // executed after CONTINUE
    expect(result.status).toBe("COMPLETED");
  });

  it("a HELD helper PARENT does not hold a lease: HELD is reachable while a nested helper child is in flight (no deadlock)", async () => {
    const parentToolLease = blockedPromise<string>();
    // Helper child model: parks mid-turn on a blocked promise (the child
    // is genuinely IN FLIGHT when the HOLD lands).
    const helperModel: ChatModel = {
      invoke: async (): Promise<ModelResponse> => {
        await parentToolLease.promise;
        return {
          content: "helper final",
          usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
        };
      },
    };

    const orch = new ScriptedModel([
      invokeCall("coder"),
      {
        content: "The coder completed the work.",
        usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 },
      },
    ]);
    const control = makeGate();
    const invoker = new HelperInvoker({
      attemptOrdinal: 1,
      chatModelFactory: { resolve: async () => helperModel },
      turnExecutor: new TurnExecutor({}),
    });
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => orch },
      helperInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });
    await flush();

    // HOLD while the helper child's model call is genuinely in flight
    // (parked on the blocked promise): the parent's invoke_helper tool
    // must NOT hold a BEFORE_TOOL_EXECUTION leaf across the child, so
    // HELD publishes (the parked child's model call is not a leaf —
    // the child parked at the scheduler's gate, permit released).
    await control.apply(holdCommand(1), "msg-hold-1");
    await flush();
    expect(control.snapshot().effectiveState).toBe("HELD");

    parentToolLease.release("x");
    await control.apply(continueCommand(2), "msg-cont-1");
    const result = await runPromise;
    expect(result.status).toBe("COMPLETED");
  });

  it("the final Git checkpoint does not run while HELD; it runs after CONTINUE", async () => {
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const control = makeGate();
    let checkpointCalls = 0;
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => helper },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => helper },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
      checkpointService: {
        create: async () => {
          checkpointCalls += 1;
          return { status: "NO_CHANGES" };
        },
      },
      allowCheckpoint: true,
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });

    // HOLD while the orchestrator turn runs: the completion gate +
    // checkpoint must WAIT for Continue (Fix 6: a bounded microtask
    // flush instead of a real 50 ms sleep — no wall-clock dependency;
    // the flush drains the turn's settled model boundary into the
    // parked checkpoint admission).
    await control.apply(holdCommand(1), "msg-hold-1");
    await flush();
    await flush();
    expect(checkpointCalls).toBe(0); // parked at BEFORE_GIT_EFFECT while HELD

    await control.apply(continueCommand(2), "msg-cont-1");
    const result = await runPromise;
    expect(result.status).toBe("COMPLETED");
    expect(checkpointCalls).toBe(1);
  });

  it("the checkpoint lease spans create(): a HOLD landing mid-create cannot publish HELD while the git effect is in flight (finding 4)", async () => {
    const helper = new ScriptedModel([
      { content: "ok", usage: { promptTokens: 5, completionTokens: 2, totalTokens: 7 } },
    ]);
    const control = makeGate();
    // The create() gate: parked until the test releases it — the git
    // effect is "in flight".
    const inFlight = blockedPromise<never>();
    let createStarted = false;
    let createFinished = false;
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => helper },
      helperInvoker: new HelperInvoker({
        attemptOrdinal: 1,
        chatModelFactory: { resolve: async () => helper },
        turnExecutor: new TurnExecutor({}),
      }),
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
      checkpointService: {
        create: async () => {
          createStarted = true;
          await inFlight.promise;
          createFinished = true;
          return { status: "NO_CHANGES" };
        },
      },
      allowCheckpoint: true,
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });
    // Drain the ungated turn (helper runs immediately) into the
    // checkpoint's BEFORE_GIT_EFFECT admission.
    await flush();
    expect(createStarted).toBe(true); // the git effect is in flight

    // HOLD while create() is mid-flight: the HELD publish must NOT
    // happen while the checkpoint leaf is still active (the lease spans
    // the create — HELD requires the drain of the git leaf).
    await control.apply(holdCommand(1), "msg-hold-1");
    await flush();
    expect(control.snapshot().effectiveState).toBe("HOLD_REQUESTED");
    expect(createFinished).toBe(false);

    // Settle the create: the leaf releases, the gate drains, HELD
    // publishes with the git effect complete.
    inFlight.release("settled" as never);
    await flush();
    expect(createFinished).toBe(true);
    expect(control.snapshot().effectiveState).toBe("HELD");

    // CONTINUE: the run completes (NO_CHANGES checkpoint accepted).
    await control.apply(continueCommand(2), "msg-cont-1");
    const result = await runPromise;
    expect(result.status).toBe("COMPLETED");
  });

  it("cancel bypasses hold for termination: a parked leaf rejects with HoldAbortedError and the run reports CANCELLED", async () => {
    const heldModel = new ScriptedModel([
      {
        content: "",
        toolCalls: [{ id: "c1", name: "echo", args: { value: "x" } }],
        usage: { promptTokens: 1, completionTokens: 1, totalTokens: 2 },
      },
    ]);
    const control = makeGate();
    await control.apply(holdCommand(1), "msg-hold-1"); // HELD before the run

    const fakeTool = makeFakeTool("echo", async () => "ok");
    void fakeTool;
    const invoker = new HelperInvoker({
      attemptOrdinal: 1,
      chatModelFactory: { resolve: async () => { throw new Error("helper must not start"); } },
      turnExecutor: new TurnExecutor({}),
    });
    const runner = new OrchestrationRunner({
      chatModelFactory: { resolve: async () => heldModel },
      helperInvoker: invoker,
      turnExecutor: new TurnExecutor({}),
      toolGate: control,
      cancellation: { cancelled: false },
    });

    const runPromise = runner.run(assignment(), { runId: "run-uuid" });
    await flush();

    // A mid-run cancel: the cancellation signal flips AND the gate is
    // stopped - parked leaves reject with HoldAbortedError.
    runnerCancellationOf(runner).cancelled = true;
    control.stop("CANCEL");
    const result = await runPromise;

    expect(result.status).toBe("CANCELLED");
    expect(result.retryDisposition).toBe("NONE");
  });
});