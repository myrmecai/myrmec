// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { describe, expect, test } from "vitest";
import {
  ApprovalPolicyEvaluator,
  governedActionDigest,
} from "./ApprovalPolicyEvaluator.js";
import type { OrchestrationAssignment, WorkerAuthoring } from "./types.js";
import type { GovernedAction } from "./GovernedAction.js";

const worker = (overrides: Partial<WorkerAuthoring> = {}): WorkerAuthoring => ({
  name: "coder",
  modelCode: "orch-model",
  capability: "implementation",
  allowedTools: ["read_file", "write_file"],
  allowedCommands: [],
  ...overrides,
});

const action = (overrides: Partial<GovernedAction> = {}): GovernedAction => ({
  actionId: "action-1",
  type: "WORKER_TOOL",
  riskClass: "DESTRUCTIVE",
  summary: "worker:coder:IMPLEMENT",
  digest: "a".repeat(64),
  ...overrides,
});

const assignment = (
  overrides: {
    approvalPolicy?: Record<string, "ALLOW" | "DENY" | "REQUIRE_APPROVAL">;
    commandTemplates?: OrchestrationAssignment["policy"]["commandTemplates"];
    workers?: WorkerAuthoring[];
  } = {},
): OrchestrationAssignment =>
  ({
    schemaVersion: "1.0",
    dispatch: {
      workflowId: "11111111-1111-5111-8111-111111111111",
      runId: "22222222-2222-5222-8222-222222222222",
      stepId: "step-1",
      taskId: "33333333-3333-5333-8333-333333333333",
      attemptId: "44444444-4444-5444-8444-444444444444",
      attemptOrdinal: 1,
      dispatchId: "44444444-4444-5444-8444-444444444444",
    },
    models: [
      {
        code: "orch-model",
        provider: "stub",
        modelId: "orch-model",
        description: "m",
        apiEndpoint: null,
        credentialRef: null,
        parameters: {},
      },
    ],
    source: {
      repoUrl: "https://example.com/repo.git",
      sourceBranch: "main",
      sourceBaseCommit: "a".repeat(40),
      targetBranch: "feat/x",
      credentialRef: null,
    },
    policy: {
      allowedTools: ["read_file", "write_file", "execute_command"],
      commandTemplates: {
        dangerous: {
          executable: "./run",
          args: [],
          parameters: {},
          cwdPattern: ".",
          environmentAllowlist: [],
          timeoutSeconds: 60,
          maxOutputBytes: 1024,
          network: "DENY",
          maxCpuSeconds: 30,
          maxMemoryBytes: 1024 * 1024,
          riskClass: "DESTRUCTIVE",
        },
        ...(overrides.commandTemplates ?? {}),
      },
      requiredIsolation: "TRUSTED_PROCESS",
      approvalPolicy: overrides.approvalPolicy ?? { "action:CHECKPOINT": "ALLOW" },
      gitPolicy: { allowCheckpoint: true, allowPush: false },
      workspaceRetentionSeconds: 3600,
    },
    step: {
      id: "step-1",
      name: "Step",
      taskType: "ORCHESTRATOR",
      agentProfileCode: "governed-coding",
      dependsOn: [],
      retryPolicy: { maxRetries: 0, initialBackoffSeconds: 1, maxBackoffSeconds: 1 },
      orchestration: {
        modelCode: "orch-model",
        goal: "g",
        specPath: null,
        sourceSubPath: "app",
        workers: overrides.workers ?? [worker()],
        checkpointStrategy: {
          mode: "ON_VERIFICATION_PASS",
          commitMessage: "feat: x",
          pushToRemote: false,
          allowNoChanges: true,
        },
        completionCriteria: { definitionOfDone: "d", requireVerificationBy: [] },
        budget: {
          maxTokens: 1000,
          maxWorkerCalls: 10,
          maxVerifierRejectionsPerAttempt: 3,
          maxOrchestratorIterations: 20,
          maxWorkerIterations: 10,
          onBudgetExceeded: "FAIL",
        },
      },
    },
  }) as unknown as OrchestrationAssignment;

describe("ApprovalPolicyEvaluator (§17.4)", () => {
  test("an explicit profile DENY always denies — no request is created", () => {
    const evaluator = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: true });
    const decision = evaluator.evaluate(
      assignment({ approvalPolicy: { "tool:write_file": "DENY" } }),
      action(),
    );
    expect(decision).toMatchObject({
      outcome: "DENY",
      reasonCode: "APPROVAL_POLICY_DENIED",
    });
  });

  test("an explicit REQUIRE_APPROVAL suspends regardless of the project setting", () => {
    // autoHitlOnDestructive=false would ALLOW by the matrix — the
    // explicit profile rule tightens past the floor.
    const evaluator = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: false });
    const decision = evaluator.evaluate(
      assignment({ approvalPolicy: { "tool:write_file": "REQUIRE_APPROVAL" } }),
      action({ riskClass: "SAFE" }),
    );
    expect(decision.outcome).toBe("REQUIRE_APPROVAL");
  });

  test("SAFE actions execute without approval (matrix floor)", () => {
    const evaluator = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: true });
    const decision = evaluator.evaluate(assignment(), action({ riskClass: "SAFE" }));
    expect(decision.outcome).toBe("ALLOW");
  });

  test("DESTRUCTIVE suspends only when the project HITL setting requires it", () => {
    const strict = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: true });
    expect(strict.evaluate(assignment(), action()).outcome).toBe("REQUIRE_APPROVAL");

    const lenient = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: false });
    expect(lenient.evaluate(assignment(), action()).outcome).toBe("ALLOW");
  });

  test("runner-owned actions map to their fixed keys", () => {
    const evaluator = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: false });
    // CHECKPOINT is DESTRUCTIVE (fixed) — DENY on action:CHECKPOINT denies.
    expect(
      evaluator.evaluate(
        assignment({ approvalPolicy: { "action:CHECKPOINT": "DENY" } }),
        action({ type: "CHECKPOINT", riskClass: "DESTRUCTIVE" }),
      ).outcome,
    ).toBe("DENY");
    // PUSH is IRREVERSIBLE (fixed) — REQUIRE_APPROVAL suspends.
    expect(
      evaluator.evaluate(
        assignment({ approvalPolicy: { "action:PUSH": "REQUIRE_APPROVAL" } }),
        action({ type: "PUSH", riskClass: "IRREVERSIBLE" }),
      ).outcome,
    ).toBe("REQUIRE_APPROVAL");
  });

  test("effective worker risk: the highest template override wins", () => {
    const evaluator = new ApprovalPolicyEvaluator({ autoHitlOnDestructive: true });
    const a = assignment({
      workers: [worker({ allowedCommands: ["dangerous"] })],
    });
    expect(evaluator.effectiveWorkerRisk(a, a.step.orchestration.workers[0])).toBe(
      "DESTRUCTIVE",
    );
    // No commands → the five tools are SAFE by default.
    const b = assignment({ workers: [worker()] });
    expect(evaluator.effectiveWorkerRisk(b, b.step.orchestration.workers[0])).toBe("SAFE");
  });

  test("the governed-action digest is deterministic over runner-owned fields", () => {
    const base = action();
    expect(governedActionDigest(base)).toBe(governedActionDigest(base));
    expect(governedActionDigest(base)).not.toBe(
      governedActionDigest({ ...base, summary: "worker:coder:VERIFY" }),
    );
    expect(governedActionDigest(base)).toMatch(/^[0-9a-f]{64}$/);
  });
});