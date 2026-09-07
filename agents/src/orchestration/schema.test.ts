// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Contract tests for the orchestration authoring DSL (design §6, §6.3)
 * and the compiled assignment (design §7).
 *
 * These tests are written BEFORE the schema (TDD per the plan): every
 * rejection rule below must fail first, then pass once schema.ts implements
 * it. Field names and shapes are the frozen design contract.
 */
import { describe, it, expect } from "vitest";
import {
  workflowDefinitionSchema,
  orchestrationAssignmentSchema,
  compileStepAssignment,
} from "./schema.js";
import type {
  WorkflowDefinition,
  OrchestrationAssignment,
  ResolvedSource,
  ExecutionPolicy,
  RetryPolicy,
} from "./types.js";

// ── helpers ──────────────────────────────────────────────────────────

/**
 * Deep-mutable view of a validated fixture for negative tests: these tests
 * deliberately poke invalid values into arbitrary fields and then rely on
 * `workflowDefinitionSchema.parse()` / `orchestrationAssignmentSchema.parse()`
 * to reject them. `any` is the honest type for "arbitrary field mutation"
 * and is confined to this one helper via a documented disable.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Mutable = { [key: string]: any };

/**
 * Widen a fixture to a deep-mutable view (cloned, so the source fixture
 * object is never corrupted between tests).
 */
function mutable<T>(value: T): Mutable {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return structuredClone(value) as any;
}

/** A minimal valid workflow definition per design §6 (models included). */
function validWorkflow(): unknown {
  return {
    version: "1.0",
    id: "w1",
    name: "Test workflow",
    source: {
      repoUrl: "https://example.com/repo.git",
      sourceBranch: "main",
      targetBranch: "feat/test",
      accessToken: "",
    },
    models: validModels(),
    workflow: [
      {
        id: "step-1",
        name: "Step one",
        taskType: "ORCHESTRATOR",
        agentProfileCode: "governed-coding",
        dependsOn: [],
        retryPolicy: { maxRetries: 1, initialBackoffSeconds: 2, maxBackoffSeconds: 30 },
        orchestration: {
          modelCode: "orch-model",
          goal: "Do the thing.",
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
            {
              name: "verifier",
              modelCode: "worker-model",
              capability: "Checks code",
              allowedTools: ["read_file"],
              allowedCommands: ["mvnw"],
            },
          ],
          checkpointStrategy: {
            mode: "ON_VERIFICATION_PASS",
            commitMessage: "feat: do the thing",
            pushToRemote: false,
            allowNoChanges: false,
          },
          completionCriteria: {
            definitionOfDone: "Thing is done.",
            requireVerificationBy: ["verifier"],
          },
          budget: {
            maxTokens: 100000,
            maxWorkerCalls: 10,
            maxVerifierRejectionsPerAttempt: 3,
            maxOrchestratorIterations: 20,
            maxWorkerIterations: 10,
            onBudgetExceeded: "PAUSE_FOR_HUMAN_REVIEW",
          },
        },
      },
    ],
  };
}

/** Minimal valid models list covering the step's references. */
function validModels(): unknown[] {
  return [
    {
      code: "orch-model",
      provider: "ollama",
      modelId: "orch:latest",
      description: "orchestrator",
      apiEndpoint: "http://localhost:11434/v1",
      apiKey: "test-key",
      parameters: {},
    },
    {
      code: "worker-model",
      provider: "ollama",
      modelId: "worker:latest",
      description: "worker",
      apiEndpoint: "http://localhost:11434/v1",
      apiKey: "",
      parameters: {},
    },
  ];
}

/** Runtime source with the resolved immutable base commit. */
function resolvedSource(): ResolvedSource {
  return {
    repoUrl: "https://example.com/repo.git",
    sourceBranch: "main",
    sourceBaseCommit: "a".repeat(40),
    targetBranch: "feat/test",
    accessToken: "git-secret",
  };
}

/** A minimal pinned-profile policy block (design §7 ExecutionPolicy). */
function executionPolicy(): ExecutionPolicy {
  return {
    allowedTools: ["read_file", "write_file", "execute_command"],
    commandTemplates: {
      mvnw: {
        executable: "./mvnw",
        args: ["test"],
        parameters: {},
        cwdPattern: "app",
        environmentAllowlist: [],
        timeoutSeconds: 300,
        maxOutputBytes: 1024 * 1024,
        network: "DENY",
        maxCpuSeconds: 60,
        maxMemoryBytes: 1024 * 1024 * 512,
        riskClass: "SAFE",
      },
    },
    requiredIsolation: "TRUSTED_PROCESS",
    approvalPolicy: { "action:CHECKPOINT": "ALLOW", "action:PUSH": "ALLOW" },
    gitPolicy: { allowCheckpoint: true, allowPush: false },
    workspaceRetentionSeconds: 3600,
  };
}

// ── workflow definition schema ───────────────────────────────────────

describe("workflowDefinitionSchema", () => {
  it("accepts the minimal valid workflow", () => {
    const parsed = workflowDefinitionSchema.parse(validWorkflow());
    expect(parsed.version).toBe("1.0");
  });

  it("rejects unknown top-level fields", () => {
    const wf = validWorkflow() as Record<string, unknown>;
    wf.surprise = true;
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects duplicate model codes", () => {
    const wf = mutable(validWorkflow());
    wf.models.push(structuredClone(wf.models[0]));
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects an unknown orchestration modelCode reference", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.modelCode = "missing-model";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects an unknown worker modelCode reference", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.workers[0].modelCode = "missing-model";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects duplicate worker names within a step", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.workers[1].name = "coder";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects duplicate step ids", () => {
    const wf = mutable(validWorkflow());
    const step = structuredClone(wf.workflow[0]);
    wf.workflow.push(step);
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects a verifier name not in the worker catalog", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.completionCriteria.requireVerificationBy = ["ghost"];
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects execute_command without allowedCommands", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.workers[0].allowedTools.push("execute_command");
    wf.workflow[0].orchestration.workers[0].allowedCommands = [];
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects unknown step dependencies", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].dependsOn = ["nope"];
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects dependency cycles", () => {
    const wf = mutable(validWorkflow());
    const a = wf.workflow[0];
    const b = structuredClone(a);
    b.id = "step-2";
    a.dependsOn = ["step-2"];
    b.dependsOn = ["step-1"];
    wf.workflow.push(b);
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects mixed agentProfileCode values across orchestration steps", () => {
    const wf = mutable(validWorkflow());
    const b = structuredClone(wf.workflow[0]);
    b.id = "step-2";
    b.agentProfileCode = "other-profile";
    wf.workflow.push(b);
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects a legacy bare maxRetries step field", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].maxRetries = 2;
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects omitted retryPolicy iteration/rejection limits", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.budget.maxOrchestratorIterations = undefined;
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
    const wf2 = mutable(validWorkflow());
    wf2.workflow[0].orchestration.budget.maxWorkerIterations = undefined;
    expect(() => workflowDefinitionSchema.parse(wf2)).toThrow();
  });

  it("rejects invalid retry backoffs", () => {
    const retryMutations: Array<{ apply: (r: RetryPolicy) => void }> = [
      { apply: (r) => (r.maxRetries = -1) },
      { apply: (r) => (r.initialBackoffSeconds = 0) },
      { apply: (r) => (r.maxBackoffSeconds = 0) },
      { apply: (r) => (r.maxBackoffSeconds = r.initialBackoffSeconds - 1) },
    ];
    for (const mutation of retryMutations) {
      const wf = mutable(validWorkflow());
      mutation.apply(wf.workflow[0].retryPolicy);
      expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
    }
  });

  it("rejects onOverrun as an alias for onBudgetExceeded", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.budget.onOverrun = "FAIL";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("accepts onBudgetExceeded as the single canonical overflow field", () => {
    const wf = validWorkflow();
    expect(() => workflowDefinitionSchema.parse(wf)).not.toThrow();
  });

  it("rejects non-positive budgets", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.budget.maxTokens = 0;
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects escaping sourceSubPath", () => {
    for (const bad of ["../outside", "/abs/path", "", "a/../b"]) {
      const wf = mutable(validWorkflow());
      wf.workflow[0].orchestration.sourceSubPath = bad;
      expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
    }
  });

  it("rejects escaping specPath", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.specPath = "../outside/spec.md";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });

  it("rejects unsupported checkpoint modes", () => {
    const wf = mutable(validWorkflow());
    wf.workflow[0].orchestration.checkpointStrategy.mode = "ON_PHASE_END";
    expect(() => workflowDefinitionSchema.parse(wf)).toThrow();
  });
});

// ── compileStepAssignment ────────────────────────────────────────────

describe("compileStepAssignment", () => {
  const base = () => {
    const wf = workflowDefinitionSchema.parse(validWorkflow()) as WorkflowDefinition;
    const models = validModels() as import("./types.js").ModelAuthoring[];
    return { wf, models };
  };

  it("compiles the selected step into a self-contained assignment", () => {
    const { wf, models } = base();
    const assignment = compileStepAssignment({
      workflow: wf,
      models,
      stepId: "step-1",
      source: resolvedSource(),
      policy: executionPolicy(),
      dispatch: {
        workflowId: "wf-uuid",
        runId: "run-uuid",
        stepId: "step-1",
        taskId: "task-uuid",
        attemptId: "attempt-uuid",
        attemptOrdinal: 1,
        dispatchId: "attempt-uuid",
      },
    });
    expect(assignment.step.id).toBe("step-1");
    expect(assignment.dispatch.stepId).toBe("step-1");
    expect(assignment.models.map((m) => m.code)).toEqual(
      expect.arrayContaining(["orch-model", "worker-model"]),
    );
    expect(assignment.source.sourceBaseCommit).toBe("a".repeat(40));
    expect(assignment.policy.commandTemplates).toHaveProperty("mvnw");
    expect(assignment.continuation).toBeUndefined();
  });

  it("never serializes secrets: apiKey/accessToken become credentialRef", () => {
    const { wf, models } = base();
    const assignment = compileStepAssignment({
      workflow: wf,
      models,
      stepId: "step-1",
      source: resolvedSource(),
      policy: executionPolicy(),
      dispatch: {
        workflowId: "wf-uuid",
        runId: "run-uuid",
        stepId: "step-1",
        taskId: "task-uuid",
        attemptId: "attempt-uuid",
        attemptOrdinal: 1,
        dispatchId: "attempt-uuid",
      },
    });
    const json = JSON.stringify(assignment);
    expect(json).not.toContain("test-key");
    expect(json).not.toContain("git-secret");
    for (const m of assignment.models) {
      expect(m.credentialRef).toBeDefined();
      expect((m as unknown as Record<string, unknown>).apiKey).toBeUndefined();
    }
    expect(assignment.source.credentialRef).toBeDefined();
    expect((assignment.source as unknown as Record<string, unknown>).accessToken).toBeUndefined();
  });

  it("rejects an unknown stepId", () => {
    const { wf, models } = base();
    expect(() =>
      compileStepAssignment({
        workflow: wf,
        models,
        stepId: "nope",
        source: resolvedSource(),
        policy: executionPolicy(),
        dispatch: {
          workflowId: "wf-uuid",
          runId: "run-uuid",
          stepId: "nope",
          taskId: "task-uuid",
          attemptId: "attempt-uuid",
          attemptOrdinal: 1,
          dispatchId: "attempt-uuid",
        },
      }),
    ).toThrow();
  });

  it("rejects a dispatch wired to a different step (stepId mismatch)", () => {
    const { wf, models } = base();
    expect(() =>
      compileStepAssignment({
        workflow: wf,
        models,
        stepId: "step-1",
        source: resolvedSource(),
        policy: executionPolicy(),
        dispatch: {
          workflowId: "wf-uuid",
          runId: "run-uuid",
          stepId: "WRONG",
          taskId: "task-uuid",
          attemptId: "attempt-uuid",
          attemptOrdinal: 1,
          dispatchId: "attempt-uuid",
        },
      }),
    ).toThrow();
  });

  it("restricts policy commandTemplates to the ones this step references", () => {
    const { wf, models } = base();
    const fatPolicy = executionPolicy();
    fatPolicy.commandTemplates = {
      ...fatPolicy.commandTemplates,
      "unreferenced-tool": { ...fatPolicy.commandTemplates.mvnw, executable: "x" },
    };
    const assignment = compileStepAssignment({
      workflow: wf,
      models,
      stepId: "step-1",
      source: resolvedSource(),
      policy: fatPolicy,
      dispatch: {
        workflowId: "wf-uuid",
        runId: "run-uuid",
        stepId: "step-1",
        taskId: "task-uuid",
        attemptId: "attempt-uuid",
        attemptOrdinal: 1,
        dispatchId: "attempt-uuid",
      },
    });
    expect(Object.keys(assignment.policy.commandTemplates)).toEqual(["mvnw"]);
  });

  it("fails closed when a referenced command template is missing from the policy", () => {
    const { wf, models } = base();
    const thinPolicy = executionPolicy();
    delete thinPolicy.commandTemplates.mvnw;
    expect(() =>
      compileStepAssignment({
        workflow: wf,
        models,
        stepId: "step-1",
        source: resolvedSource(),
        policy: thinPolicy,
        dispatch: {
          workflowId: "wf-uuid",
          runId: "run-uuid",
          stepId: "step-1",
          taskId: "task-uuid",
          attemptId: "attempt-uuid",
          attemptOrdinal: 1,
          dispatchId: "attempt-uuid",
        },
      }),
    ).toThrow();
  });

  it("includes exactly the models the selected step references", () => {
    const { wf, models } = base();
    models.push({
      code: "unused-model",
      provider: "ollama",
      modelId: "unused:latest",
      description: "not referenced",
      apiEndpoint: "http://localhost:11434/v1",
      apiKey: "",
      parameters: {},
    });
    const assignment = compileStepAssignment({
      workflow: wf,
      models,
      stepId: "step-1",
      source: resolvedSource(),
      policy: executionPolicy(),
      dispatch: {
        workflowId: "wf-uuid",
        runId: "run-uuid",
        stepId: "step-1",
        taskId: "task-uuid",
        attemptId: "attempt-uuid",
        attemptOrdinal: 1,
        dispatchId: "attempt-uuid",
      },
    });
    expect(assignment.models.map((m) => m.code)).not.toContain("unused-model");
  });
});

// ── orchestrationAssignmentSchema (runtime boundary validation) ─────

describe("orchestrationAssignmentSchema", () => {
  function validAssignment(): OrchestrationAssignment {
    const wf = workflowDefinitionSchema.parse(validWorkflow()) as WorkflowDefinition;
    return compileStepAssignment({
      workflow: wf,
      models: validModels() as import("./types.js").ModelAuthoring[],
      stepId: "step-1",
      source: resolvedSource(),
      policy: executionPolicy(),
      dispatch: {
        workflowId: "wf-uuid",
        runId: "run-uuid",
        stepId: "step-1",
        taskId: "task-uuid",
        attemptId: "attempt-uuid",
        attemptOrdinal: 1,
        dispatchId: "attempt-uuid",
      },
    });
  }

  it("round-trips a compiled assignment", () => {
    const a = validAssignment();
    const parsed = orchestrationAssignmentSchema.parse(JSON.parse(JSON.stringify(a)));
    expect(parsed.dispatch.dispatchId).toBe("attempt-uuid");
    expect(parsed.policy.commandTemplates.mvnw.executable).toBe("./mvnw");
  });

  it("rejects a worker command template not present in policy", () => {
    const a = validAssignment();
    const mutated = mutable(a);
    mutated.step.orchestration.workers[1].allowedCommands = ["ghost-template"];
    expect(() => orchestrationAssignmentSchema.parse(mutated)).toThrow();
  });

  it("rejects a worker tool outside the closed tool set", () => {
    const a = validAssignment();
    const mutated = mutable(a);
    mutated.step.orchestration.workers[0].allowedTools = ["rm_rf"];
    expect(() => orchestrationAssignmentSchema.parse(mutated)).toThrow();
  });

  it("rejects a worker tool not allowed by the policy", () => {
    const a = validAssignment();
    const mutated = mutable(a);
    // Narrow the policy allowlist so the worker's execute_command is a
    // closed-set tool the policy does not permit.
    mutated.policy.allowedTools = ["read_file", "write_file", "list_directory"];
    mutated.step.orchestration.workers[0].allowedTools = ["write_file", "execute_command"];
    mutated.step.orchestration.workers[0].allowedCommands = ["mvnw"];
    expect(() => orchestrationAssignmentSchema.parse(mutated)).toThrow();
  });

  it("rejects dispatch.stepId mismatch with step.id", () => {
    const a = validAssignment();
    const mutated = mutable(a);
    mutated.dispatch.stepId = "WRONG";
    expect(() => orchestrationAssignmentSchema.parse(mutated)).toThrow();
  });

  it("rejects an assignment whose raw apiKey sneaks through", () => {
    const a = validAssignment();
    const mutated = mutable(a);
    mutated.models[0].apiKey = "leaked";
    expect(() => orchestrationAssignmentSchema.parse(mutated)).toThrow();
  });

  it("rejects unknown top-level assignment fields", () => {
    const a = mutable(validAssignment());
    a.surprise = true;
    expect(() => orchestrationAssignmentSchema.parse(a)).toThrow();
  });
});