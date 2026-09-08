// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Orchestration contract schemas (design §6 authoring DSL, §7 runtime
 * assignment) and the pure `compileStepAssignment()` compiler.
 *
 * Everything here is pure: no file access, environment lookup, network
 * call, or registry query. Credentials are extracted by the compiler into
 * an adapter-private scope and replaced with opaque `credentialRef`s;
 * secret values are never serialized in an assignment.
 */
import { z } from "zod";
import type { RiskClass } from "./types.js";

const ORCHESTRATION_TOOL_TUPLE = [
  "read_file",
  "list_directory",
  "create_directory",
  "write_file",
  "execute_command",
] as const satisfies readonly string[];

type OrchestrationToolTuple = typeof ORCHESTRATION_TOOL_TUPLE;
const orchestrationToolEnum = z.enum(
  ORCHESTRATION_TOOL_TUPLE as unknown as [OrchestrationToolTuple[number], ...OrchestrationToolTuple[number][]],
);

// ── shared primitives ────────────────────────────────────────────────

/** Relative path inside the checkout: non-empty, no absolute form, no
 * `..` traversal, normalized forward slashes. */
const relativePath = z
  .string()
  .min(1)
  .refine((v) => !v.includes("\\"), { message: "use forward slashes" })
  .refine((v) => !v.startsWith("/"), { message: "absolute paths are not allowed" })
  .refine((v) => !v.split("/").includes(".."), { message: "path traversal is not allowed" });

const riskClass = z.enum(["SAFE", "DESTRUCTIVE", "IRREVERSIBLE"] satisfies [
  RiskClass,
  ...RiskClass[],
]);

// ── authoring DSL (design §6) ─────────────────────────────────────────

const retryPolicy = z
  .object({
    maxRetries: z.number().int().min(0),
    initialBackoffSeconds: z.number().int().min(1),
    maxBackoffSeconds: z.number().int().min(1),
  })
  .strict()
  .refine((v) => v.maxBackoffSeconds >= v.initialBackoffSeconds, {
    message: "maxBackoffSeconds must be >= initialBackoffSeconds",
  });

const workerAuthoring = z
  .object({
    name: z.string().min(1),
    modelCode: z.string().min(1),
    capability: z.string().min(1),
    allowedTools: z.array(orchestrationToolEnum),
    allowedCommands: z.array(z.string().min(1)),
  })
  .strict()
  .superRefine((w, ctx) => {
    if (w.allowedTools.includes("execute_command") && w.allowedCommands.length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["allowedCommands"],
        message: "execute_command requires a non-empty allowedCommands policy",
      });
    }
  });

const orchestrationPolicy = z
  .object({
    modelCode: z.string().min(1),
    goal: z.string().min(1),
    specPath: relativePath.nullable(),
    sourceSubPath: relativePath,
    workers: z.array(workerAuthoring).min(1),
    checkpointStrategy: z
      .object({
        mode: z.literal("ON_VERIFICATION_PASS"),
        commitMessage: z.string().min(1),
        pushToRemote: z.boolean(),
        allowNoChanges: z.boolean(),
      })
      .strict(),
    completionCriteria: z
      .object({
        definitionOfDone: z.string().min(1),
        requireVerificationBy: z.array(z.string().min(1)),
      })
      .strict(),
    budget: z
      .object({
        maxTokens: z.number().int().positive(),
        maxWorkerCalls: z.number().int().positive(),
        maxVerifierRejectionsPerAttempt: z.number().int().positive(),
        maxOrchestratorIterations: z.number().int().positive(),
        maxWorkerIterations: z.number().int().positive(),
        onBudgetExceeded: z.enum(["FAIL", "PAUSE_FOR_HUMAN_REVIEW"]),
      })
      .strict()
      .refine((v) => !("onOverrun" in (v as object)), {
        message: "onOverrun is not valid; use onBudgetExceeded",
      }),
  })
  .strict();

const stepAuthoring = z
  .object({
    id: z.string().min(1),
    name: z.string().min(1),
    taskType: z.literal("ORCHESTRATOR"),
    agentProfileCode: z.string().min(1),
    dependsOn: z.array(z.string().min(1)),
    retryPolicy,
    orchestration: orchestrationPolicy,
  })
  .strict();

const modelAuthoring = z
  .object({
    code: z.string().min(1),
    provider: z.string().min(1),
    modelId: z.string().min(1),
    description: z.string().min(1),
    apiEndpoint: z.string().nullable(),
    apiKey: z.string(),
    parameters: z.record(z.string(), z.unknown()),
  })
  .strict();

const workflowDefinition = z
  .object({
    version: z.literal("1.0"),
    id: z.string().min(1),
    name: z.string().min(1),
    source: z
      .object({
        repoUrl: z.string().min(1),
        sourceBranch: z.string().min(1),
        targetBranch: z.string().min(1),
        accessToken: z.string(),
      })
      .strict(),
    models: z.array(modelAuthoring).min(1),
    workflow: z.array(stepAuthoring).min(1),
  })
  .strict()
  .superRefine((wf, ctx) => {
    const add = (path: (string | number)[], message: string) =>
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path,
        message,
      });

    // Duplicate model codes.
    const modelCodes = new Set<string>();
    wf.models.forEach((m, i) => {
      if (modelCodes.has(m.code)) add(["models", i, "code"], "duplicate model code");
      modelCodes.add(m.code);
    });

    // One identical agentProfileCode across every orchestration step.
    const profileCodes = new Set(wf.workflow.map((s) => s.agentProfileCode));
    if (profileCodes.size > 1) {
      add(["workflow"], "all orchestration steps must use one agentProfileCode in V1");
    }

    // Step-level cross references.
    const stepIds = new Set(wf.workflow.map((s) => s.id));
    wf.workflow.forEach((step, si) => {
      // Duplicate step ids.
      if (wf.workflow.filter((s) => s.id === step.id).length > 1) {
        add(["workflow", si, "id"], "duplicate step id");
      }
      // Unknown dependencies.
      step.dependsOn.forEach((dep, di) => {
        if (!stepIds.has(dep)) {
          add(["workflow", si, "dependsOn", di], `unknown dependency: ${dep}`);
        }
      });
      // Dependency cycles (DFS with stack).
      const visiting = new Set<string>();
      const detect = (id: string, chain: Set<string>): boolean => {
        if (chain.has(id)) return true;
        if (visiting.has(id)) return false;
        visiting.add(id);
        chain.add(id);
        const s = wf.workflow.find((x) => x.id === id);
        for (const d of s?.dependsOn ?? []) {
          if (detect(d, new Set(chain))) return true;
        }
        chain.delete(id);
        return false;
      };
      if (detect(step.id, new Set())) {
        add(["workflow", si, "dependsOn"], "dependency cycle detected");
      }

      // Orchestration references.
      const o = step.orchestration;
      if (!modelCodes.has(o.modelCode)) {
        add(["workflow", si, "orchestration", "modelCode"], `unknown model code: ${o.modelCode}`);
      }
      const workerNames = new Set<string>();
      o.workers.forEach((w, wi) => {
        if (workerNames.has(w.name)) {
          add(["workflow", si, "orchestration", "workers", wi, "name"], "duplicate worker name");
        }
        workerNames.add(w.name);
        if (!modelCodes.has(w.modelCode)) {
          add(
            ["workflow", si, "orchestration", "workers", wi, "modelCode"],
            `unknown model code: ${w.modelCode}`,
          );
        }
      });
      for (const [vi, verifier] of o.completionCriteria.requireVerificationBy.entries()) {
        if (!workerNames.has(verifier)) {
          add(
            ["workflow", si, "orchestration", "completionCriteria", "requireVerificationBy", vi],
            `verifier not in worker catalog: ${verifier}`,
          );
        }
      }
    });
  });

export const workflowDefinitionSchema = workflowDefinition;

// ── runtime assignment (design §7) ────────────────────────────────────

const commandTemplateDefinition = z
  .object({
    executable: z.string().min(1),
    args: z.array(z.union([z.string(), z.object({ parameter: z.string().min(1) }).strict()])),
    parameters: z.record(
      z.string(),
      z
        .object({
          type: z.enum(["string", "integer", "boolean"]),
          required: z.boolean(),
          pattern: z.string().optional(),
          allowedValues: z.array(z.union([z.string(), z.number(), z.boolean()])).optional(),
        })
        .strict(),
    ),
    cwdPattern: z.string().min(1),
    environmentAllowlist: z.array(z.string()),
    timeoutSeconds: z.number().int().positive(),
    maxOutputBytes: z.number().int().positive(),
    network: z.enum(["DENY", "ALLOW"]),
    maxCpuSeconds: z.number().int().positive(),
    maxMemoryBytes: z.number().int().positive(),
    riskClass: riskClass.optional(),
  })
  .strict();

const executionPolicy = z
  .object({
    allowedTools: z.array(orchestrationToolEnum),
    commandTemplates: z.record(z.string(), commandTemplateDefinition),
    requiredIsolation: z.enum(["TRUSTED_PROCESS", "UNTRUSTED_REPOSITORY"]),
    approvalPolicy: z.record(z.string(), z.enum(["ALLOW", "DENY", "REQUIRE_APPROVAL"])),
    gitPolicy: z
      .object({ allowCheckpoint: z.boolean(), allowPush: z.boolean() })
      .strict(),
    workspaceRetentionSeconds: z.number().int().positive(),
    approvalRequestTtlSeconds: z.number().int().positive().optional(),
  })
  .strict();

const modelDefinition = z
  .object({
    code: z.string().min(1),
    provider: z.string().min(1),
    modelId: z.string().min(1),
    description: z.string().min(1),
    apiEndpoint: z.string().nullable(),
    credentialRef: z.string().min(1).nullable(),
    parameters: z.record(z.string(), z.unknown()),
  })
  .strict()
  .refine((m) => !("apiKey" in (m as object)), { message: "raw apiKey is forbidden" });

const sourceDefinition = z
  .object({
    repoUrl: z.string().min(1),
    sourceBranch: z.string().min(1),
    sourceBaseCommit: z.string().regex(/^[0-9a-f]{40}$/, "must be a full commit SHA"),
    targetBranch: z.string().min(1),
    credentialRef: z.string().min(1).nullable(),
  })
  .strict()
  .refine((s) => !("accessToken" in (s as object)), {
    message: "raw accessToken is forbidden",
  });

const dispatchIdentity = z
  .object({
    workflowId: z.string().min(1),
    runId: z.string().min(1),
    stepId: z.string().min(1),
    taskId: z.string().min(1),
    attemptId: z.string().min(1),
    attemptOrdinal: z.number().int().min(1),
    dispatchId: z.string().min(1),
    continuationId: z.string().min(1).optional(),
  })
  .strict();

const runtimeStep = stepAuthoring;

/** §7 ApprovalDecision — the typed decision a HITL-resume continuation
 * carries. The runner validates every field against the restored
 * suspension before resuming the exact pending action. */
const approvalDecision = z
  .object({
    schemaVersion: z.literal("1.0"),
    decisionId: z.string().min(1),
    approvalRequestId: z.string().min(1),
    continuationId: z.string().min(1),
    previousDispatchId: z.string().min(1),
    status: z.enum(["APPROVED", "REJECTED"]),
    actionDigest: z.string().min(1),
    stateDigest: z.string().min(1),
    snapshotTreeHash: z.string().min(1),
    workspaceGeneration: z.number().int().nonnegative(),
    decidedAt: z.string().min(1),
    expiresAt: z.string().min(1),
  })
  .strict();

const orchestrationAssignment = z
  .object({
    schemaVersion: z.literal("1.0"),
    dispatch: dispatchIdentity,
    models: z.array(modelDefinition).min(1),
    source: sourceDefinition,
    policy: executionPolicy,
    step: runtimeStep,
    continuation: z
      .object({
        continuationId: z.string().min(1),
        previousDispatchId: z.string().min(1),
        /** §7/§17.4: present only on a HITL-resume continuation — the
         * typed decision the runner validates against the restored
         * suspension before resuming the exact pending action. */
        decision: approvalDecision.optional(),
      })
      .strict()
      .optional(),
  })
  .strict()
  .superRefine((a, ctx) => {
    const add = (path: (string | number)[], message: string) =>
      ctx.addIssue({ code: z.ZodIssueCode.custom, path, message });

    if (a.dispatch.stepId !== a.step.id) {
      add(["dispatch", "stepId"], "dispatch.stepId must equal step.id");
    }

    // Referenced templates exist in the policy.
    const templateNames = new Set(Object.keys(a.policy.commandTemplates));
    a.step.orchestration.workers.forEach((w, wi) => {
      for (const [ci, cmd] of w.allowedCommands.entries()) {
        if (!templateNames.has(cmd)) {
          add(
            ["step", "orchestration", "workers", wi, "allowedCommands", ci],
            `command template not in policy: ${cmd}`,
          );
        }
      }
      // Worker tools are within the policy allowlist.
      for (const [ti, tool] of w.allowedTools.entries()) {
        if (!a.policy.allowedTools.includes(tool)) {
          add(
            ["step", "orchestration", "workers", wi, "allowedTools", ti],
            `tool not allowed by policy: ${tool}`,
          );
        }
      }
    });

    // Referenced models exist in the assignment catalog.
    const modelCodes = new Set(a.models.map((m) => m.code));
    if (!modelCodes.has(a.step.orchestration.modelCode)) {
      add(["step", "orchestration", "modelCode"], "orchestration model not in assignment models");
    }
    a.step.orchestration.workers.forEach((w, wi) => {
      if (!modelCodes.has(w.modelCode)) {
        add(
          ["step", "orchestration", "workers", wi, "modelCode"],
          "worker model not in assignment models",
        );
      }
    });

    // Continuation presence matches the dispatch.
    if (a.dispatch.continuationId && a.continuation?.continuationId !== a.dispatch.continuationId) {
      add(["continuation"], "dispatch.continuationId must match assignment.continuation");
    }
    if (a.continuation && !a.dispatch.continuationId) {
      add(["continuation"], "continuation present without dispatch.continuationId");
    }
    // §7: a decision-bearing continuation binds the same continuation +
    // prior dispatch; a decision without the directive fields fails.
    if (a.continuation?.decision) {
      if (a.continuation.decision.continuationId !== a.continuation.continuationId) {
        add(["continuation", "decision"], "decision.continuationId must match the continuation");
      }
      if (a.continuation.decision.previousDispatchId !== a.continuation.previousDispatchId) {
        add(
          ["continuation", "decision"],
          "decision.previousDispatchId must match the continuation",
        );
      }
    }
  });

export const orchestrationAssignmentSchema = orchestrationAssignment;

// ── compileStepAssignment ─────────────────────────────────────────────

/** Adapter-private credential scope: the compiler registers secrets here
 * and hands the scope back to the caller; refs are opaque ids. */
export interface CredentialScope {
  register(secret: string): string;
  resolve(ref: string): string | undefined;
}

export function createCredentialScope(): CredentialScope {
  const store = new Map<string, string>();
  let counter = 0;
  return {
    register(secret) {
      const ref = `cred-${++counter}`;
      store.set(ref, secret);
      return ref;
    },
    resolve(ref) {
      return store.get(ref);
    },
  };
}

export interface CompileStepAssignmentInput {
  workflow: z.infer<typeof workflowDefinitionSchema>;
  models: import("./types.js").ModelAuthoring[];
  stepId: string;
  source: import("./types.js").ResolvedSource;
  policy: import("./types.js").ExecutionPolicy;
  dispatch: import("./types.js").DispatchIdentity;
  /** Adapter-supplied scope; one is created if absent (rare — adapters
   * need the scope back to resolve credentials later). */
  credentialScope?: CredentialScope;
  continuation?: import("./types.js").ContinuationDirective;
}

/**
 * Compile exactly one validated step into a self-contained runtime
 * assignment (design §7). Pure: no IO of any kind. Extracts secrets into
 * the credential scope, includes only referenced models, and restricts the
 * policy's command templates to the ones this step's workers reference.
 */
export function compileStepAssignment(input: CompileStepAssignmentInput): import("./types.js").OrchestrationAssignment {
  const { workflow, models, stepId, source, policy, dispatch } = input;
  const scope = input.credentialScope ?? createCredentialScope();

  const step = workflow.workflow.find((s) => s.id === stepId);
  if (!step) {
    throw new Error(`unknown step: ${stepId}`);
  }
  if (dispatch.stepId !== step.id) {
    throw new Error(`dispatch wired to a different step: ${dispatch.stepId} != ${step.id}`);
  }

  // Exactly the models the step references: orchestrator + every worker.
  const referencedCodes = new Set<string>([step.orchestration.modelCode]);
  for (const w of step.orchestration.workers) referencedCodes.add(w.modelCode);
  const stepModels = models.filter((m) => referencedCodes.has(m.code));
  if (referencedCodes.size !== stepModels.length) {
    const missing = [...referencedCodes].filter(
      (c) => !stepModels.some((m) => m.code === c),
    );
    throw new Error(`missing model definitions: ${missing.join(", ")}`);
  }

  // Referenced-subset command templates.
  const referencedTemplates = new Set<string>();
  for (const w of step.orchestration.workers) {
    for (const cmd of w.allowedCommands) referencedTemplates.add(cmd);
  }
  for (const name of referencedTemplates) {
    if (!policy.commandTemplates[name]) {
      throw new Error(`command template missing from policy: ${name}`);
    }
  }
  const stepTemplates: typeof policy.commandTemplates = {};
  for (const name of referencedTemplates) {
    stepTemplates[name] = policy.commandTemplates[name]!;
  }

  const assignment: import("./types.js").OrchestrationAssignment = {
    schemaVersion: "1.0",
    dispatch,
    models: stepModels.map((m) => ({
      code: m.code,
      provider: m.provider,
      modelId: m.modelId,
      description: m.description,
      apiEndpoint: m.apiEndpoint,
      credentialRef: m.apiKey ? scope.register(m.apiKey) : null,
      parameters: m.parameters,
    })),
    source: {
      repoUrl: source.repoUrl,
      sourceBranch: source.sourceBranch,
      sourceBaseCommit: source.sourceBaseCommit,
      targetBranch: source.targetBranch,
      credentialRef: source.accessToken ? scope.register(source.accessToken) : null,
    },
    policy: { ...policy, commandTemplates: stepTemplates },
    step,
    ...(input.continuation ? { continuation: input.continuation } : {}),
  };

  // Final boundary check: the compiled assignment must validate.
  const result = orchestrationAssignmentSchema.safeParse(assignment);
  if (!result.success) {
    const first = result.error.issues[0];
    throw new Error(
      `compiled assignment failed validation: ${first?.path.join(".") ?? ""}: ${first?.message ?? "unknown"}`,
    );
  }
  return result.data as import("./types.js").OrchestrationAssignment;
}